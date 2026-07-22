import hashlib
from uuid import uuid4

from fastapi.testclient import TestClient

from apvero_worker.main import app

client = TestClient(app)


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


def _digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()
