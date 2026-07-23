from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[2] / "apps" / "ai-worker"))
from benchmarks.parser_candidates import minimal_docx, minimal_pdf

API_ORIGIN = os.environ.get("APVERO_VERIFY_ORIGIN", "http://127.0.0.1:8080")
WORKSPACE_ID = "00000000-0000-0000-0000-000000000101"
TOKEN = os.environ.get("APVERO_BOOTSTRAP_ADMIN_TOKEN", "apvero-compose-verification")


def request(
    path: str,
    *,
    method: str = "GET",
    body: object | None = None,
    multipart: tuple[str, str, bytes] | None = None,
) -> object:
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "X-Apvero-Workspace-Id": WORKSPACE_ID,
        "X-Request-Id": str(uuid4()),
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if multipart is not None:
        name, filename, content = multipart
        boundary = f"apvero-{uuid4().hex}"
        data = (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="name"\r\n\r\n'
            f"{name}\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    call = urllib.request.Request(API_ORIGIN + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=15) as response:
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        problem = json.loads(payload) if payload else {}
        raise ApiFailure(error.code, problem) from error


class ApiFailure(RuntimeError):
    def __init__(self, status: int, problem: dict):
        super().__init__(f"API returned {status}: {problem}")
        self.status = status
        self.problem = problem


def create_base(prefix: str) -> dict:
    suffix = uuid4().hex[:12]
    return request(
        "/api/v1/knowledge-bases",
        method="POST",
        body={"slug": f"{prefix}-{suffix}", "name": f"{prefix} {suffix}", "description": ""},
    )


def wait_job(job_id: str, expected: set[str], timeout: float = 90) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = request(f"/api/v1/knowledge-ingestion-jobs/{job_id}")
        if last["status"] in expected:
            return last
        if last["status"] in {"FAILED", "CANCELLED"} and last["status"] not in expected:
            raise AssertionError(f"job {job_id} terminated unexpectedly: {last}")
        time.sleep(0.25)
    raise AssertionError(f"job {job_id} did not reach {sorted(expected)}: {last}")


def create_inline(base_id: str, source_type: str, name: str, content: str) -> dict:
    return request(
        f"/api/v1/knowledge-bases/{base_id}/sources",
        method="POST",
        body={"sourceType": source_type, "name": name, "content": content},
    )


def create_upload(base_id: str, name: str, filename: str, content: bytes) -> dict:
    return request(
        f"/api/v1/knowledge-bases/{base_id}/source-uploads",
        method="POST",
        multipart=(name, filename, content),
    )


def verify_full_workflow() -> None:
    base = create_base("compose-e2e")
    receipts = [
        create_inline(base["id"], "TEXT", "Text", "Deterministic plain text."),
        create_inline(base["id"], "MARKDOWN", "Markdown", "# Policy\n\nApproved."),
        create_upload(base["id"], "PDF", "proof.pdf", minimal_pdf()),
        create_upload(base["id"], "DOCX", "proof.docx", minimal_docx()),
        request(
            f"/api/v1/knowledge-bases/{base['id']}/sources",
            method="POST",
            body={
                "sourceType": "WEB",
                "name": "Public HTML",
                "url": "https://example.com/",
            },
        ),
    ]
    for receipt in receipts:
        job = wait_job(receipt["job"]["id"], {"READY"})
        assert job["currentStep"] == "COMPLETE"
        source_id = receipt["source"]["id"]
        revisions = request(f"/api/v1/knowledge-sources/{source_id}/revisions")
        assert revisions
        revision = revisions[0]
        snapshot = request_bytes(
            f"/api/v1/knowledge-source-revisions/{revision['id']}/content"
        )
        assert snapshot

    web_source = receipts[-1]["source"]["id"]
    sync = request(f"/api/v1/knowledge-sources/{web_source}/sync", method="POST")
    unchanged = wait_job(sync["job"]["id"], {"READY"})
    assert unchanged["syncOutcome"] == "UNCHANGED"

    tombstoned_source = receipts[0]["source"]["id"]
    request(f"/api/v1/knowledge-sources/{tombstoned_source}", method="DELETE")
    try:
        request(
            f"/api/v1/knowledge-sources/{tombstoned_source}/revisions",
            method="POST",
            body={"content": "must be rejected"},
        )
    except ApiFailure as failure:
        assert failure.status == 409
        assert failure.problem["code"] == "APVERO_KNOWLEDGE_SOURCE_TOMBSTONED"
    else:
        raise AssertionError("a tombstoned source accepted a new revision")


def request_bytes(path: str) -> bytes:
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "X-Apvero-Workspace-Id": WORKSPACE_ID,
    }
    call = urllib.request.Request(API_ORIGIN + path, headers=headers)
    with urllib.request.urlopen(call, timeout=15) as response:
        return response.read()


def create_retry_source() -> None:
    base = create_base("restart-e2e")
    receipt = create_inline(base["id"], "TEXT", "Restart", "Persist before restart.")
    print(receipt["job"]["id"])


def wait_from_cli(job_id: str, statuses: list[str]) -> None:
    print(json.dumps(wait_job(job_id, set(statuses)), sort_keys=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("full")
    subcommands.add_parser("create-retry-source")
    waiter = subcommands.add_parser("wait-job")
    waiter.add_argument("job_id")
    waiter.add_argument("statuses", nargs="+")
    args = parser.parse_args()
    if args.command == "full":
        verify_full_workflow()
    elif args.command == "create-retry-source":
        create_retry_source()
    else:
        wait_from_cli(args.job_id, args.statuses)


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"Knowledge Compose verification failed: {failure}", file=sys.stderr)
        raise
