from __future__ import annotations

import argparse
import json
import statistics
import time

from benchmarks.parser_candidates import candidate_cases, output_digest


def run(iterations: int) -> dict[str, object]:
    results: list[dict[str, object]] = []
    for case_id, (parser, content) in candidate_cases().items():
        expected = parser(content)
        expected_digest = output_digest(expected)
        samples: list[float] = []
        for _ in range(iterations):
            started = time.perf_counter_ns()
            actual = parser(content)
            samples.append((time.perf_counter_ns() - started) / 1_000_000)
            if output_digest(actual) != expected_digest:
                raise RuntimeError(f"non-deterministic parser output: {case_id}")
        results.append(
            {
                "caseId": case_id,
                "inputBytes": len(content),
                "outputCodePoints": len(expected),
                "outputDigest": expected_digest,
                "iterations": iterations,
                "medianMilliseconds": round(statistics.median(samples), 3),
                "maximumMilliseconds": round(max(samples), 3),
            }
        )
    return {
        "profile": "apvero-parser-candidate-benchmark@1.0.0",
        "claim": "synthetic-smoke-only",
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--iterations", type=int, default=25)
    args = parser.parse_args()
    if args.iterations < 2:
        raise SystemExit("iterations must be at least 2")
    print(json.dumps(run(args.iterations), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
