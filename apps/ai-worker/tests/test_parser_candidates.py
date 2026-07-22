import json

from benchmarks.parser_candidates import CORPUS_ROOT, candidate_cases, output_digest, parse_html


def test_candidate_outputs_are_deterministic() -> None:
    for parser, content in candidate_cases().values():
        first = parser(content)
        second = parser(content)
        assert first
        assert output_digest(first) == output_digest(second)


def test_html_candidate_removes_active_content() -> None:
    result = parse_html(b"<main>retained</main><script>secret()</script><style>x{}</style>")

    assert result == "retained"


def test_candidate_corpus_covers_all_p2_1_media_types() -> None:
    assert set(candidate_cases()) == {
        "text-unicode",
        "markdown-structure",
        "html-active-content",
        "pdf-one-page",
        "docx-headings",
    }


def test_candidate_outputs_match_the_versioned_baseline() -> None:
    manifest = json.loads((CORPUS_ROOT / "manifest.json").read_text(encoding="utf-8"))
    expected = {case["id"]: case["expectedOutputDigest"] for case in manifest["cases"]}

    actual = {
        case_id: output_digest(parser(content))
        for case_id, (parser, content) in candidate_cases().items()
    }

    assert actual == expected
