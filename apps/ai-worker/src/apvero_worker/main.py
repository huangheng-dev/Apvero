from fastapi import FastAPI

from apvero_worker.models import (
    ChunkRequest,
    ChunkResponse,
    ExactMatchRequest,
    ExactMatchResponse,
    HealthResponse,
)
from apvero_worker.services import chunk_text, exact_match

app = FastAPI(
    title="Apvero AI Worker",
    version="0.1.0",
    description="Stateless, idempotent document and evaluation operations.",
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(service="apvero-ai-worker", status="healthy", version="0.1.0")


@app.post("/v1/chunk", response_model=ChunkResponse)
def chunk(request: ChunkRequest) -> ChunkResponse:
    return chunk_text(request)


@app.post("/v1/evaluate/exact-match", response_model=ExactMatchResponse)
def evaluate_exact_match(request: ExactMatchRequest) -> ExactMatchResponse:
    return exact_match(request)
