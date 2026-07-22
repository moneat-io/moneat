#!/usr/bin/env python3
"""Exercise mixed telemetry admission, saturation, outage, and recovery phases.

This client never changes server state. With --interactive-outage it prompts an
operator to stop and later restore ClickHouse while requests continue entering
the Redis-backed ingestion queues.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field


DEFAULT_TIMEOUT_SECONDS = 10.0


@dataclass
class PhaseResult:
    name: str
    statuses: Counter[int] = field(default_factory=Counter)
    errors: Counter[str] = field(default_factory=Counter)
    latencies_ms: list[float] = field(default_factory=list)

    def record(self, status: int, latency_ms: float, error: str | None) -> None:
        self.statuses[status] += 1
        self.latencies_ms.append(latency_ms)
        if error:
            self.errors[error] += 1

    def summary(self) -> dict[str, object]:
        ordered = sorted(self.latencies_ms)
        p95_index = max(0, min(len(ordered) - 1, int(len(ordered) * 0.95)))
        return {
            "phase": self.name,
            "requests": sum(self.statuses.values()),
            "statuses": dict(sorted(self.statuses.items())),
            "errors": dict(self.errors),
            "latency_ms_p95": round(ordered[p95_index], 2) if ordered else None,
        }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Backend origin, for example http://localhost:8088")
    parser.add_argument("--api-key", required=True, help="Datadog-compatible agent API key")
    parser.add_argument("--normal-rps", type=float, default=5.0)
    parser.add_argument("--burst-rps", type=float, default=100.0)
    parser.add_argument("--saturation-rps", type=float, default=250.0)
    parser.add_argument("--normal-seconds", type=float, default=15.0)
    parser.add_argument("--burst-seconds", type=float, default=10.0)
    parser.add_argument("--saturation-seconds", type=float, default=20.0)
    parser.add_argument("--outage-seconds", type=float, default=20.0)
    parser.add_argument("--recovery-seconds", type=float, default=30.0)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument(
        "--interactive-outage",
        action="store_true",
        help="Prompt before the outage phase and before recovery so an operator can stop/restore ClickHouse",
    )
    return parser.parse_args()


def metric_payload(sequence: int, now_seconds: int) -> tuple[str, bytes]:
    body = {
        "series": [
            {
                "metric": "loadtest.system.cpu.user",
                "points": [[now_seconds, float(sequence % 100)]],
                "host": f"loadtest-{sequence % 8}",
                "tags": ["env:loadtest", "source:mixed-ingestion"],
                "type": "gauge",
            }
        ]
    }
    return "/dd/api/v1/series", encode(body)


def service_check_payload(sequence: int, now_seconds: int) -> tuple[str, bytes]:
    body = {
        "service_checks": [
            {
                "check": "loadtest.queue.ready",
                "host_name": f"loadtest-{sequence % 8}",
                "status": 0,
                "timestamp": now_seconds,
                "tags": ["env:loadtest"],
            }
        ]
    }
    return "/dd/api/v2/service_checks", encode(body)


def event_payload(sequence: int, now_seconds: int) -> tuple[str, bytes]:
    body = {
        "events": [
            {
                "title": "Mixed ingestion load test",
                "text": f"sequence={sequence}",
                "date_happened": now_seconds,
                "host": f"loadtest-{sequence % 8}",
                "tags": ["env:loadtest"],
            }
        ]
    }
    return "/dd/api/v2/events", encode(body)


def encode(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def send_request(
    base_url: str,
    api_key: str,
    timeout: float,
    sequence: int,
) -> tuple[int, float, str | None]:
    payload_factory = (metric_payload, service_check_payload, event_payload)[sequence % 3]
    path, body = payload_factory(sequence, int(time.time()))
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "DD-API-KEY": api_key,
            "User-Agent": "moneat-mixed-ingestion-load-test/1",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, elapsed_ms(started), None
    except urllib.error.HTTPError as error:
        if error.code == 429:
            retry_after = error.headers.get("Retry-After")
            return error.code, elapsed_ms(started), None if retry_after else "missing-retry-after"
        return error.code, elapsed_ms(started), error.reason
    except (OSError, TimeoutError) as error:
        return 0, elapsed_ms(started), type(error).__name__


def elapsed_ms(started: float) -> float:
    return (time.perf_counter() - started) * 1000.0


def run_phase(
    executor: ThreadPoolExecutor,
    base_url: str,
    api_key: str,
    timeout: float,
    name: str,
    rps: float,
    duration_seconds: float,
    sequence_start: int,
) -> tuple[PhaseResult, int]:
    result = PhaseResult(name)
    futures = []
    interval = 1.0 / max(rps, 0.1)
    deadline = time.monotonic() + duration_seconds
    next_send = time.monotonic()
    sequence = sequence_start
    while time.monotonic() < deadline:
        sleep_seconds = next_send - time.monotonic()
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)
        futures.append(executor.submit(send_request, base_url, api_key, timeout, sequence))
        sequence += 1
        next_send += interval

    for future in as_completed(futures):
        status, latency_ms, error = future.result()
        result.record(status, latency_ms, error)
    print(json.dumps(result.summary(), sort_keys=True), flush=True)
    return result, sequence


def prompt(message: str) -> None:
    if not sys.stdin.isatty():
        raise RuntimeError("--interactive-outage requires an interactive terminal")
    input(message)


def main() -> int:
    args = parse_args()
    sequence = random.randint(1, 1_000_000)
    results: list[PhaseResult] = []
    phases = [
        ("normal", args.normal_rps, args.normal_seconds),
        ("burst", args.burst_rps, args.burst_seconds),
        ("queue-saturation", args.saturation_rps, args.saturation_seconds),
    ]

    with ThreadPoolExecutor(max_workers=max(args.workers, 1), thread_name_prefix="ingestion-load") as executor:
        for name, rps, duration in phases:
            result, sequence = run_phase(
                executor, args.base_url, args.api_key, args.timeout, name, rps, duration, sequence
            )
            results.append(result)

        if args.interactive_outage:
            prompt("Stop ClickHouse now, then press Enter to run the outage phase: ")
        outage, sequence = run_phase(
            executor,
            args.base_url,
            args.api_key,
            args.timeout,
            "clickhouse-outage",
            args.normal_rps,
            args.outage_seconds,
            sequence,
        )
        results.append(outage)
        if args.interactive_outage:
            prompt("Restore ClickHouse and wait for it to become healthy, then press Enter for recovery: ")
        recovery, _ = run_phase(
            executor,
            args.base_url,
            args.api_key,
            args.timeout,
            "bounded-recovery",
            args.normal_rps,
            args.recovery_seconds,
            sequence,
        )
        results.append(recovery)

    all_statuses = sum((result.statuses for result in results), Counter())
    failures: list[str] = []
    server_errors = sum(count for status, count in all_statuses.items() if status >= 500)
    if server_errors > 0:
        failures.append(f"observed {server_errors} HTTP 5xx responses")
    if all_statuses[0] > 0:
        failures.append(f"observed {all_statuses[0]} transport failures")
    saturation = next(result for result in results if result.name == "queue-saturation")
    if saturation.statuses[429] == 0:
        failures.append("queue-saturation phase did not reach a queue or ingress limit")
    missing_retry_after = sum(result.errors["missing-retry-after"] for result in results)
    if missing_retry_after > 0:
        failures.append(f"observed {missing_retry_after} HTTP 429 responses without Retry-After")
    if recovery.statuses[200] + recovery.statuses[202] == 0:
        failures.append("recovery phase had no accepted requests")

    print(json.dumps({"overall_statuses": dict(sorted(all_statuses.items())), "failures": failures}))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
