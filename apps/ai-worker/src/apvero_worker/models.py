from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, model_validator


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


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class WorkerContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True)


class SourceAnchors(WorkerContractModel):
    page: int | None = Field(default=None, ge=1)
    heading: str | None = Field(default=None, min_length=1, max_length=1_000)
    paragraph: int | None = Field(default=None, ge=1)
    line_start: int | None = Field(default=None, ge=1)
    line_end: int | None = Field(default=None, ge=1)


class ProcessedChunk(WorkerContractModel):
    ordinal: int = Field(ge=0)
    text: str = Field(min_length=1, max_length=20_000)
    content_digest: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    start_offset: int = Field(ge=0)
    end_offset: int = Field(ge=1)
    anchors: SourceAnchors


class ProcessedDocument(WorkerContractModel):
    ordinal: int = Field(ge=0)
    title: str | None = Field(default=None, min_length=1, max_length=1_000)
    content_digest: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    chunks: list[ProcessedChunk] = Field(min_length=1, max_length=100_000)


class ProcessingWarning(WorkerContractModel):
    code: str
    location: str | None = Field(default=None, max_length=500)


class ProcessedDocumentBatch(WorkerContractModel):
    request_id: str
    source_revision_id: str
    content_digest: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    processing_profile: str
    parser_version: str
    chunker_version: str
    documents: list[ProcessedDocument] = Field(min_length=1, max_length=10_000)
    warnings: list[ProcessingWarning] = Field(default_factory=list, max_length=1_000)


class WorkerProblem(WorkerContractModel):
    type: str
    title: str
    status: int = Field(ge=400, le=599)
    code: str
    retryable: bool
    request_id: str
