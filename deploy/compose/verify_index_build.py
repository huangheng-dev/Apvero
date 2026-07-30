from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from uuid import uuid4

API_ORIGIN = os.environ.get("APVERO_VERIFY_ORIGIN", "http://127.0.0.1:8080")
TOKEN = os.environ.get("APVERO_BOOTSTRAP_ADMIN_TOKEN", "apvero-compose-verification")
PRIMARY_WORKSPACE = "00000000-0000-0000-0000-000000000101"
SECONDARY_WORKSPACE = "00000000-0000-0000-0000-000000000102"
PRIMARY_INDEX = "00000000-0000-0000-0000-000000006001"
SECONDARY_INDEX = "00000000-0000-0000-0000-000000007002"
PRIMARY_REVISION = "00000000-0000-0000-0000-000000005301"
SECONDARY_REVISION = "00000000-0000-0000-0000-000000006302"
PRIMARY_ROUTE = "00000000-0000-0000-0000-000000005901"
SECONDARY_ROUTE = "00000000-0000-0000-0000-000000006902"
FIXTURE_PATH = Path(__file__).with_name("p2-2d-5-fixtures.sql")
REPOSITORY_ROOT = Path(__file__).parents[2]


class VerificationError(RuntimeError):
    pass


EXERCISED_BUILD_METRICS = {
    "apvero.knowledge.index.build.claimed": {"step"},
    "apvero.knowledge.index.build.queue.wait": {"step"},
    "apvero.knowledge.index.build.step.duration": {
        "step",
        "outcome",
        "error_category",
    },
    "apvero.knowledge.index.build.attempt": {"step", "attempt_bucket"},
    "apvero.knowledge.index.build.batch.items": {"outcome"},
    "apvero.knowledge.index.build.batch.units": {"quality", "outcome"},
    "apvero.knowledge.index.build.entries": {"kind", "outcome"},
    "apvero.knowledge.index.build.recovery": {"action", "outcome"},
    "apvero.knowledge.index.build.publication.validation": {
        "outcome",
        "error_category",
    },
    "apvero.knowledge.index.build.publication": {"outcome"},
    "apvero.knowledge.index.build.inflight": set(),
    "apvero.knowledge.index.build.oldest.eligible.age": set(),
    "apvero.knowledge.index.build.reconciliation": set(),
}


class ApiFailure(RuntimeError):
    def __init__(self, status: int, problem: dict):
        super().__init__(f"API returned {status}: {problem.get('code', 'UNKNOWN')}")
        self.status = status
        self.problem = problem


def request(
    path: str,
    *,
    workspace: str = PRIMARY_WORKSPACE,
    method: str = "GET",
    body: object | None = None,
    timeout: float = 15,
) -> object:
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "X-Apvero-Workspace-Id": workspace,
        "X-Request-Id": str(uuid4()),
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    call = urllib.request.Request(
        API_ORIGIN + path,
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(call, timeout=timeout) as response:
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        problem = json.loads(payload) if payload else {}
        raise ApiFailure(error.code, problem) from error


def compose_command() -> list[str]:
    return [
        "docker",
        "compose",
        "--profile",
        "knowledge",
        "-f",
        str(REPOSITORY_ROOT / "deploy" / "compose" / "compose.yaml"),
        "-f",
        str(REPOSITORY_ROOT / "deploy" / "compose" / "compose.knowledge.yaml"),
    ]


def psql(*, sql: str | None = None, file: Path | None = None) -> str:
    if (sql is None) == (file is None):
        raise ValueError("exactly one SQL input is required")
    command = [
        *compose_command(),
        "exec",
        "-T",
        "postgres",
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        os.environ.get("APVERO_DB_USER", "apvero"),
        "-d",
        os.environ.get("APVERO_DB_NAME", "apvero"),
    ]
    if sql is not None:
        command.extend(["-A", "-t", "-c", sql])
        input_text = None
    else:
        input_text = file.read_text(encoding="utf-8")
    try:
        completed = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            check=True,
            capture_output=True,
            text=True,
            input=input_text,
            timeout=60,
        )
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "psql failed without output").strip()
        raise VerificationError(f"PostgreSQL assertion failed: {detail}") from error
    return completed.stdout.strip()


def create_build(
    workspace: str,
    index_id: str,
    version: str,
    route_id: str,
    revision_id: str,
) -> dict:
    return request(
        f"/api/v1/knowledge-indexes/{index_id}/builds",
        workspace=workspace,
        method="POST",
        body={
            "version": version,
            "embeddingRouteId": route_id,
            "sourceRevisionIds": [revision_id],
        },
    )


def get_build(workspace: str, build_id: str) -> dict:
    return request(
        f"/api/v1/knowledge-index-builds/{build_id}",
        workspace=workspace,
    )


def find_build(workspace: str, index_id: str, version: str) -> dict:
    builds = request(
        f"/api/v1/knowledge-indexes/{index_id}/builds",
        workspace=workspace,
    )
    matching = [build for build in builds if build["version"] == version]
    assert len(matching) == 1, (workspace, index_id, version, matching)
    return matching[0]


def wait_build(
    workspace: str,
    build_id: str,
    expected: set[str],
    *,
    timeout: float = 90,
) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = get_build(workspace, build_id)
        if last["status"] in expected:
            return last
        if last["status"] in {"FAILED", "CANCELLED"} and last["status"] not in expected:
            raise AssertionError(f"Build terminated unexpectedly: {last}")
        time.sleep(0.2)
    raise AssertionError(f"Build did not reach {sorted(expected)}: {last}")


def runner_health() -> dict:
    health = request("/actuator/health")
    component = health.get("components", {}).get("knowledgeIndexBuildRunner")
    assert component is not None, health
    return component


def assert_not_found(callable_request) -> None:
    try:
        callable_request()
    except ApiFailure as failure:
        assert failure.status == 404, failure.problem
    else:
        raise AssertionError("cross-workspace operation did not fail closed")


def bootstrap_disabled() -> None:
    psql(file=FIXTURE_PATH)
    primary = create_build(
        PRIMARY_WORKSPACE,
        PRIMARY_INDEX,
        "1.0.0",
        PRIMARY_ROUTE,
        PRIMARY_REVISION,
    )
    secondary = create_build(
        SECONDARY_WORKSPACE,
        SECONDARY_INDEX,
        "2.0.0",
        SECONDARY_ROUTE,
        SECONDARY_REVISION,
    )
    time.sleep(1.25)
    primary = get_build(PRIMARY_WORKSPACE, primary["id"])
    secondary = get_build(SECONDARY_WORKSPACE, secondary["id"])
    assert (primary["status"], primary["attemptCount"]) == ("QUEUED", 0)
    assert (secondary["status"], secondary["attemptCount"]) == ("QUEUED", 0)
    health = runner_health()
    assert health["status"] == "UP", health
    assert health["details"]["runnerEnabled"] is False, health
    assert health["details"]["lifecycle"] == "disabled", health
    print(
        json.dumps(
            {
                "disabledBuilds": [primary["id"], secondary["id"]],
                "health": health["details"],
            },
            sort_keys=True,
        )
    )


def verify_ready() -> None:
    primary = find_build(PRIMARY_WORKSPACE, PRIMARY_INDEX, "1.0.0")
    secondary = find_build(SECONDARY_WORKSPACE, SECONDARY_INDEX, "2.0.0")
    primary = wait_build(PRIMARY_WORKSPACE, primary["id"], {"READY"})
    secondary = wait_build(SECONDARY_WORKSPACE, secondary["id"], {"READY"})
    assert primary["publishedVersionId"]
    assert secondary["publishedVersionId"]
    replay = create_build(
        PRIMARY_WORKSPACE,
        PRIMARY_INDEX,
        "1.0.0",
        PRIMARY_ROUTE,
        PRIMARY_REVISION,
    )
    assert replay["id"] == primary["id"]

    assert_not_found(lambda: get_build(SECONDARY_WORKSPACE, primary["id"]))
    assert_not_found(
        lambda: request(
            f"/api/v1/knowledge-index-builds/{primary['id']}/cancel",
            workspace=SECONDARY_WORKSPACE,
            method="POST",
        )
    )

    persisted = psql(
        sql=f"""
        select concat(
            (select count(*) from knowledge_index_version
             where knowledge_index_id = '{PRIMARY_INDEX}'::uuid),
            ':',
            (select count(*) from knowledge_index_entry
             where knowledge_index_build_id = '{primary["id"]}'::uuid),
            ':',
            (select count(*) from audit_event
             where workspace_id = '{PRIMARY_WORKSPACE}'::uuid
               and action = 'knowledge.index-version.published'
               and resource_id = '{primary["publishedVersionId"]}')
        )
        """
    )
    assert persisted == "1:1:1", persisted
    verify_operational_signals()
    print(
        json.dumps(
            {
                "primaryBuild": primary["id"],
                "primaryVersion": primary["publishedVersionId"],
                "secondaryBuild": secondary["id"],
                "secondaryVersion": secondary["publishedVersionId"],
                "persisted": persisted,
            },
            sort_keys=True,
        )
    )


def verify_operational_signals() -> None:
    health = runner_health()
    details = health["details"]
    assert health["status"] == "UP", health
    assert details["runnerEnabled"] is True, health
    assert details["accepting"] is True, health
    assert details["lifecycle"] == "accepting", health
    assert details["lastScanOutcome"] == "success", health
    assert set(details) == {
        "featureEnabled",
        "runnerEnabled",
        "accepting",
        "lifecycle",
        "inFlight",
        "oldestEligibleBuildAgeSeconds",
        "reconciliationCount",
        "lastScanOutcome",
        "snapshotAgeSeconds",
    }

    samples: list[dict] = []
    for meter_name, expected_tags in EXERCISED_BUILD_METRICS.items():
        encoded = urllib.parse.quote(meter_name, safe=".")
        meter = request(f"/actuator/metrics/{encoded}")
        actual_tags = {tag["tag"] for tag in meter.get("availableTags", [])}
        assert actual_tags == expected_tags, (meter_name, actual_tags)
        samples.append(meter)

    serialized = json.dumps({"health": health, "metrics": samples}, sort_keys=True)
    forbidden = {
        PRIMARY_WORKSPACE,
        SECONDARY_WORKSPACE,
        PRIMARY_INDEX,
        SECONDARY_INDEX,
        PRIMARY_REVISION,
        SECONDARY_REVISION,
        "alpha evidence",
        "beta evidence",
        "index-build-runner-",
        "local://deterministic",
    }
    assert not [value for value in forbidden if value in serialized]


def create_recovery() -> None:
    recovery = create_build(
        PRIMARY_WORKSPACE,
        PRIMARY_INDEX,
        "1.0.1",
        PRIMARY_ROUTE,
        PRIMARY_REVISION,
    )
    recovery = get_build(PRIMARY_WORKSPACE, recovery["id"])
    assert (recovery["status"], recovery["attemptCount"]) == ("QUEUED", 0)
    print(recovery["id"])


def mark_recovery_inflight() -> None:
    recovery = find_build_in_database("1.0.1")
    psql(
        sql=f"""
        update knowledge_index_build
        set status = 'EMBEDDING',
            current_step = 'EMBEDDING',
            attempt_count = 1,
            lease_owner = 'p2-d5-dead-runner',
            lease_until = transaction_timestamp() + interval '2 seconds',
            started_at = coalesce(started_at, transaction_timestamp()),
            lock_version = lock_version + 1,
            updated_at = transaction_timestamp()
        where id = '{recovery}'::uuid
          and status = 'QUEUED'
          and attempt_count = 0
        returning id
        """
    )
    state = psql(
        sql=f"""
        select status || ':' || attempt_count || ':' || lease_owner
        from knowledge_index_build
        where id = '{recovery}'::uuid
        """
    )
    assert state == "EMBEDDING:1:p2-d5-dead-runner", state
    print(recovery)


def find_build_in_database(version: str) -> str:
    build_id = psql(
        sql=f"""
        select id
        from knowledge_index_build
        where workspace_id = '{PRIMARY_WORKSPACE}'::uuid
          and knowledge_index_id = '{PRIMARY_INDEX}'::uuid
          and requested_version = '{version}'
        """
    )
    assert build_id, version
    return build_id


def verify_recovery() -> None:
    recovery_id = find_build_in_database("1.0.1")
    recovered = wait_build(PRIMARY_WORKSPACE, recovery_id, {"READY"}, timeout=120)
    persisted = psql(
        sql=f"""
        select concat(
            (select version_count from knowledge_index
             where id = '{PRIMARY_INDEX}'::uuid),
            ':',
            (select count(*) from knowledge_index_version
             where knowledge_index_id = '{PRIMARY_INDEX}'::uuid),
            ':',
            (select count(*) from knowledge_index_entry
             where knowledge_index_build_id = '{recovery_id}'::uuid)
        )
        """
    )
    assert persisted == "2:2:1", persisted
    # Reclaiming an expired in-flight lease resumes the same durable attempt.
    # Only QUEUED and RETRY_WAIT claims consume a new attempt.
    assert recovered["attemptCount"] == 1, recovered
    verify_operational_signals()
    print(
        json.dumps(
            {
                "recoveredBuild": recovery_id,
                "publishedVersion": recovered["publishedVersionId"],
                "attemptCount": recovered["attemptCount"],
                "persisted": persisted,
            },
            sort_keys=True,
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices={
            "bootstrap-disabled",
            "verify-ready",
            "create-recovery",
            "mark-recovery-inflight",
            "verify-recovery",
        },
    )
    args = parser.parse_args()
    {
        "bootstrap-disabled": bootstrap_disabled,
        "verify-ready": verify_ready,
        "create-recovery": create_recovery,
        "mark-recovery-inflight": mark_recovery_inflight,
        "verify-recovery": verify_recovery,
    }[args.command]()


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"Index Build Compose verification failed: {failure}", file=sys.stderr)
        raise
