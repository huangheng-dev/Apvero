import hashlib
from pathlib import Path
from uuid import uuid4

import yaml
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

from apvero_worker.main import app

client = TestClient(app)
CONTRACT_PATH = Path(__file__).parents[3] / "contracts" / "openapi" / "ai-worker-internal.v1.yaml"


def test_health_contract() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"


def test_invalid_chunk_request_is_rejected() -> None:
    response = client.post(
        "/v1/chunk",
        json={"document_id": "x", "text": "short", "chunk_size": 100, "overlap": 100},
    )
    assert response.status_code == 422


def test_internal_document_processing_contract_uses_camel_case_and_stable_identity() -> None:
    request_id = uuid4()
    revision_id = uuid4()
    content = b"# Guide\nUse immutable releases."
    response = client.post(
        "/internal/v1/documents/process",
        data={
            "requestId": str(request_id),
            "sourceRevisionId": str(revision_id),
            "contentDigest": _digest(content),
            "mediaType": "text/markdown",
            "processingProfile": "apvero-default@1.0.0",
        },
        files={"content": ("snapshot.bin", content, "application/octet-stream")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == str(request_id)
    assert body["sourceRevisionId"] == str(revision_id)
    assert body["contentDigest"] == _digest(content)
    assert body["parserVersion"] == "apvero-markdown@1.0.0"
    assert body["chunkerVersion"] == "apvero-boundary@1.0.0"
    assert body["documents"][0]["chunks"][0]["anchors"]["heading"] == "Guide"


def test_internal_document_processing_returns_safe_problem_details() -> None:
    request_id = uuid4()
    revision_id = uuid4()
    content = b"private source content"
    response = client.post(
        "/internal/v1/documents/process",
        data={
            "requestId": str(request_id),
            "sourceRevisionId": str(revision_id),
            "contentDigest": _digest(b"other"),
            "mediaType": "text/plain",
            "processingProfile": "apvero-default@1.0.0",
        },
        files={"content": ("secret.txt", content, "application/octet-stream")},
    )

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json() == {
        "type": "https://apvero.dev/problems/worker-content-digest-mismatch",
        "title": "WORKER_CONTENT_DIGEST_MISMATCH",
        "status": 400,
        "code": "WORKER_CONTENT_DIGEST_MISMATCH",
        "retryable": False,
        "requestId": str(request_id),
    }
    assert "private source content" not in response.text
    assert "secret.txt" not in response.text


def test_runtime_request_and_responses_conform_to_the_versioned_openapi_contract() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    runtime = app.openapi()
    contract_operation = contract["paths"]["/internal/v1/documents/process"]["post"]
    runtime_operation = runtime["paths"]["/internal/v1/documents/process"]["post"]

    contract_request = contract_operation["requestBody"]["content"]["multipart/form-data"]["schema"]
    runtime_schema = runtime_operation["requestBody"]["content"]["multipart/form-data"]["schema"]
    runtime_request = _resolve(runtime, runtime_schema)
    assert set(runtime_request["required"]) == set(contract_request["required"])
    assert set(runtime_request["properties"]) == set(contract_request["properties"])

    request_id = uuid4()
    revision_id = uuid4()
    content = b"# Contract\nThe response must match the committed OpenAPI schema."
    success = client.post(
        "/internal/v1/documents/process",
        data={
            "requestId": str(request_id),
            "sourceRevisionId": str(revision_id),
            "contentDigest": _digest(content),
            "mediaType": "text/markdown",
            "processingProfile": "apvero-default@1.0.0",
        },
        files={"content": ("snapshot.bin", content, "application/octet-stream")},
    )
    assert success.status_code == 200
    _validate_contract_instance(
        contract,
        contract_operation["responses"]["200"]["content"]["application/json"]["schema"],
        success.json(),
    )

    failure = client.post(
        "/internal/v1/documents/process",
        data={
            "requestId": str(request_id),
            "sourceRevisionId": str(revision_id),
            "contentDigest": _digest(b"different"),
            "mediaType": "text/plain",
            "processingProfile": "apvero-default@1.0.0",
        },
        files={"content": ("snapshot.bin", content, "application/octet-stream")},
    )
    assert failure.status_code == 400
    problem_response = contract["components"]["responses"]["WorkerProblem"]
    _validate_contract_instance(
        contract,
        problem_response["content"]["application/problem+json"]["schema"],
        failure.json(),
    )


def _resolve(document: dict, schema: dict) -> dict:
    if "$ref" not in schema:
        return schema
    current = document
    for segment in schema["$ref"].removeprefix("#/").split("/"):
        current = current[segment]
    return current


def _validate_contract_instance(contract: dict, schema: dict, instance: object) -> None:
    validator = Draft202012Validator(
        _dereference(contract, schema),
        format_checker=FormatChecker(),
    )
    validator.validate(instance)


def _dereference(document: dict, value: object) -> object:
    if isinstance(value, list):
        return [_dereference(document, item) for item in value]
    if not isinstance(value, dict):
        return value
    if "$ref" in value:
        return _dereference(document, _resolve(document, value))
    return {key: _dereference(document, item) for key, item in value.items()}


def _digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()
