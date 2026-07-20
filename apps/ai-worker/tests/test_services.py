import pytest

from apvero_worker.models import ChunkRequest, EvaluationCase, ExactMatchRequest
from apvero_worker.services import chunk_text, exact_match


def test_chunking_is_deterministic_and_lossless_with_overlap() -> None:
    request = ChunkRequest(
        document_id="policy-1",
        text="A" * 120 + "。" + "B" * 120 + "。" + "C" * 120,
        chunk_size=150,
        overlap=20,
    )
    first = chunk_text(request)
    second = chunk_text(request)
    assert first == second
    assert len(first.chunks) >= 3
    assert first.chunks[0].start == 0
    assert first.chunks[-1].end == len(request.text)


def test_exact_match_reports_per_case_and_aggregate_score() -> None:
    response = exact_match(
        ExactMatchRequest(
            cases=[
                EvaluationCase(case_id="one", expected="Approved", actual=" approved "),
                EvaluationCase(case_id="two", expected="Denied", actual="Unknown"),
            ]
        )
    )
    assert response.score == 0.5
    assert response.passed == 1
    assert [item.passed for item in response.results] == [True, False]


def test_overlap_must_be_smaller_than_chunk_size() -> None:
    with pytest.raises(ValueError):
        ChunkRequest(document_id="bad", text="text", chunk_size=100, overlap=100)
