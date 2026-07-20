from __future__ import annotations

from pydantic import BaseModel, Field, model_validator


class HealthResponse(BaseModel):
    service: str
    status: str
    version: str


class ChunkRequest(BaseModel):
    document_id: str = Field(min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=5_000_000)
    chunk_size: int = Field(default=800, ge=100, le=10_000)
    overlap: int = Field(default=100, ge=0, le=2_000)

    @model_validator(mode="after")
    def validate_overlap(self) -> ChunkRequest:
        if self.overlap >= self.chunk_size:
            raise ValueError("overlap must be smaller than chunk_size")
        return self


class Chunk(BaseModel):
    index: int
    start: int
    end: int
    text: str


class ChunkResponse(BaseModel):
    document_id: str
    algorithm: str
    chunks: list[Chunk]


class EvaluationCase(BaseModel):
    case_id: str = Field(min_length=1, max_length=200)
    expected: str
    actual: str


class ExactMatchRequest(BaseModel):
    cases: list[EvaluationCase] = Field(min_length=1, max_length=10_000)
    case_sensitive: bool = False
    trim_whitespace: bool = True


class EvaluationResult(BaseModel):
    case_id: str
    passed: bool


class ExactMatchResponse(BaseModel):
    metric: str
    score: float
    passed: int
    total: int
    results: list[EvaluationResult]
