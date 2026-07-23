from typing import Annotated
from uuid import UUID

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from apvero_worker.document_processing import (
    MAX_INPUT_BYTES,
    WorkerProcessingError,
    process_document_snapshot,
)
from apvero_worker.models import (
    ChunkRequest,
    ChunkResponse,
    ExactMatchRequest,
    ExactMatchResponse,
    HealthResponse,
    ProcessedDocumentBatch,
    WorkerProblem,
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


@app.post(
    "/internal/v1/documents/process",
    response_model=ProcessedDocumentBatch,
    responses={
        400: {"model": WorkerProblem},
        413: {"model": WorkerProblem},
        415: {"model": WorkerProblem},
        422: {"model": WorkerProblem},
        503: {"model": WorkerProblem},
    },
)
async def process_document(
    request_id: Annotated[str, Form(alias="requestId")],
    source_revision_id: Annotated[str, Form(alias="sourceRevisionId")],
    content_digest: Annotated[str, Form(alias="contentDigest")],
    media_type: Annotated[str, Form(alias="mediaType")],
    processing_profile: Annotated[str, Form(alias="processingProfile")],
    content: Annotated[UploadFile, File()],
) -> ProcessedDocumentBatch:
    parsed_request_id = _uuid(request_id, None)
    parsed_revision_id = _uuid(source_revision_id, parsed_request_id)
    body = await content.read(MAX_INPUT_BYTES + 1)
    return process_document_snapshot(
        request_id=parsed_request_id,
        source_revision_id=parsed_revision_id,
        content_digest=content_digest,
        media_type=media_type,
        processing_profile=processing_profile,
        content=body,
    )


@app.exception_handler(WorkerProcessingError)
def processing_problem(_request: object, exception: WorkerProcessingError) -> JSONResponse:
    return _problem_response(exception)


@app.exception_handler(RequestValidationError)
async def validation_problem(
    request: Request, exception: RequestValidationError
) -> JSONResponse:
    if request.url.path != "/internal/v1/documents/process":
        return await request_validation_exception_handler(request, exception)
    return _problem_response(
        WorkerProcessingError(
            "WORKER_INVALID_REQUEST",
            status=400,
            request_id=UUID(int=0),
        )
    )


@app.post("/v1/chunk", response_model=ChunkResponse)
def chunk(request: ChunkRequest) -> ChunkResponse:
    return chunk_text(request)


@app.post("/v1/evaluate/exact-match", response_model=ExactMatchResponse)
def evaluate_exact_match(request: ExactMatchRequest) -> ExactMatchResponse:
    return exact_match(request)


def _uuid(value: str, request_id: UUID | None) -> UUID:
    try:
        return UUID(value)
    except (TypeError, ValueError) as exception:
        raise WorkerProcessingError(
            "WORKER_INVALID_REQUEST",
            status=400,
            request_id=request_id or UUID(int=0),
        ) from exception


def _problem_response(exception: WorkerProcessingError) -> JSONResponse:
    code = exception.code
    problem = WorkerProblem(
        type="https://apvero.dev/problems/" + code.lower().replace("_", "-"),
        title=code,
        status=exception.status,
        code=code,
        retryable=exception.retryable,
        request_id=str(exception.request_id or UUID(int=0)),
    )
    return JSONResponse(
        status_code=exception.status,
        content=problem.model_dump(by_alias=True),
        media_type="application/problem+json",
    )
