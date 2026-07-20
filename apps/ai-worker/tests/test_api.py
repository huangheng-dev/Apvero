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
