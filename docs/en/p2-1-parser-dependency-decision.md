# P2.1 Parser Candidate Dependency Decision

Status: Accepted for benchmark use; production endpoint remains disabled
Date: 2026-07-22
Scope: P2.1a only

## Decision

Apvero will benchmark these explicit candidates on the Python 3.14 worker:

- `pypdf 6.14.2` for bounded text extraction from text-bearing PDF files;
- `python-docx 1.2.0` for DOCX structure access;
- `beautifulsoup4 4.15.0` with the named standard-library `html.parser` backend for captured HTML.

They are development-only benchmark dependencies in P2.1a. Moving them into the production dependency set and enabling `/internal/v1/documents/process` is reserved for P2.1e after the adversarial corpus and process limits pass.

`pypdf` is pure Python, declares Python 3.14 support, uses BSD-3-Clause, and exposes page-level text extraction. It does not provide OCR and cannot make PDF layout extraction perfect. `python-docx` uses MIT and provides paragraph/heading access, but its ZIP/XML inputs still require Apvero-owned preflight limits. Beautiful Soup uses MIT; naming `html.parser` prevents environment-dependent parser selection. Active elements are removed before text extraction.

PyMuPDF is not adopted because its AGPL/commercial licensing does not match this Apache-2.0 distribution baseline. `pdfminer.six`, `html5lib`, and alternative DOCX readers remain comparison candidates only if the representative corpus shows a concrete quality gap.

Primary references:

- https://pypi.org/project/pypdf/
- https://pypdf.readthedocs.io/en/latest/user/extract-text.html
- https://pypi.org/project/python-docx/
- https://github.com/python-openxml/python-docx/blob/master/LICENSE
- https://www.crummy.com/software/BeautifulSoup/bs4/doc/
- https://github.com/pymupdf/PyMuPDF

## Benchmark and corpus contract

Run from `apps/ai-worker`:

```bash
uv run python -m benchmarks.benchmark_parser_candidates --iterations 25
```

The committed smoke corpus covers UTF-8/code-point behavior, Markdown headings, captured HTML with active content, one generated PDF page, and generated DOCX headings. The benchmark requires identical output digests across iterations and reports input bytes, output code points, median time, and maximum time.

This small generated corpus catches dependency/runtime regressions; it does **not** establish production size, page, archive-expansion, memory, CPU, or timeout claims. Before endpoint enablement, the manifest's adversarial families require real fixtures and container measurements. Until then:

- `APVERO_KNOWLEDGE_ENABLED=false` remains the default;
- the worker has no public route or host port;
- no PDF OCR, authenticated crawl, XLSX, PPTX, or image claim is allowed;
- no parser limit may be presented as production-tested.

The initial Windows/Python 3.14 smoke run on 2026-07-22 completed 25 deterministic iterations per case. Median times were approximately 0.005 ms for text, 0.004 ms for Markdown, 0.672 ms for HTML, 0.741 ms for the generated one-page PDF, and 13.144 ms for generated DOCX. These numbers only prove that the harness executes and must not be used for capacity planning.

## Security and operability gates for P2.1e

1. Java validates authorization, immutable snapshot identity, media structure, and bounded input before calling the worker.
2. DOCX ZIP/XML preflight rejects macros, encryption, malformed archives, and excessive expansion before library traversal.
3. PDF processing rejects encryption and enforces byte/page/time/output limits in the isolated worker.
4. The worker stays non-root, read-only, resource-bounded, credential-free, database-free, and network-isolated.
5. Java validates every response digest, ordinal, code-point offset, anchor, and output bound before persistence.
6. Dependency, license, vulnerability, malformed-input, timeout, and repeatability checks must pass in CI.

## Rollback

P2.1a can remove the development-only candidates and benchmark files without data migration because no parser endpoint, persisted document, or release depends on them. The fail-closed platform configuration and private worker network remain safe independently.
