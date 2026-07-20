from apvero_worker.models import (
    Chunk,
    ChunkRequest,
    ChunkResponse,
    EvaluationResult,
    ExactMatchRequest,
    ExactMatchResponse,
)


def chunk_text(request: ChunkRequest) -> ChunkResponse:
    chunks: list[Chunk] = []
    start = 0
    while start < len(request.text):
        hard_end = min(start + request.chunk_size, len(request.text))
        end = _nearest_boundary(request.text, start, hard_end)
        if end <= start:
            end = hard_end
        value = request.text[start:end]
        chunks.append(Chunk(index=len(chunks), start=start, end=end, text=value))
        if end == len(request.text):
            break
        start = max(start + 1, end - request.overlap)
    return ChunkResponse(
        document_id=request.document_id,
        algorithm="boundary-aware-v1",
        chunks=chunks,
    )


def exact_match(request: ExactMatchRequest) -> ExactMatchResponse:
    results = [
        EvaluationResult(
            case_id=case.case_id,
            passed=_normalize(case.expected, request) == _normalize(case.actual, request),
        )
        for case in request.cases
    ]
    passed = sum(result.passed for result in results)
    return ExactMatchResponse(
        metric="exact-match-v1",
        score=passed / len(results),
        passed=passed,
        total=len(results),
        results=results,
    )


def _nearest_boundary(text: str, start: int, hard_end: int) -> int:
    if hard_end == len(text):
        return hard_end
    minimum = start + ((hard_end - start) * 3 // 5)
    for delimiter in ("\n\n", "\n", ". ", "。", "! ", "！", "? ", "？"):
        boundary = text.rfind(delimiter, minimum, hard_end)
        if boundary >= minimum:
            return boundary + len(delimiter)
    return hard_end


def _normalize(value: str, request: ExactMatchRequest) -> str:
    normalized = value.strip() if request.trim_whitespace else value
    return normalized if request.case_sensitive else normalized.casefold()
