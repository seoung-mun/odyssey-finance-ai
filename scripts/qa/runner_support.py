"""REAL runner의 계약 매핑과 성능 판정에 쓰는 표준 라이브러리 helper."""

from __future__ import annotations

import math
import re
import statistics
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
JUDGE_START = datetime(2026, 9, 7, 11, 0, tzinfo=KST)
JUDGE_END = datetime(2026, 9, 11, 23, 59, 59, 999999, tzinfo=KST)


@dataclass(frozen=True)
class Operation:
    operation_id: str
    method: str
    path: str
    pattern: re.Pattern[str]


def contract_operations(path: Path) -> list[Operation]:
    operations: list[Operation] = []
    current_path: str | None = None
    current_method: str | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        path_match = re.match(r"^  (/[^:]+):\s*$", line)
        if path_match:
            current_path = path_match.group(1)
            current_method = None
            continue
        method_match = re.match(r"^    (get|post|put|patch|delete):\s*$", line)
        if method_match and current_path:
            current_method = method_match.group(1).upper()
            continue
        operation_match = re.match(r"^      operationId:\s*(\S+)\s*$", line)
        if operation_match and current_path and current_method:
            api_path = current_path if current_path.startswith("/internal/") else "/api/v1" + current_path
            pattern = "^" + re.sub(r"\\\{[^/]+\\\}", r"[^/]+", re.escape(api_path)) + "$"
            operations.append(
                Operation(operation_match.group(1), current_method, api_path, re.compile(pattern))
            )
    return operations


def match_operation(operations: list[Operation], method: str, path: str) -> Operation | None:
    clean_path = path.split("?", 1)[0]
    return next(
        (operation for operation in operations if operation.method == method and operation.pattern.match(clean_path)),
        None,
    )


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ValueError("percentile requires at least one value")
    if not 0 < quantile <= 1:
        raise ValueError("quantile must be in (0, 1]")
    ordered = sorted(values)
    return float(ordered[math.ceil(len(ordered) * quantile) - 1])


def three_run_summary(runs: list[dict[str, float | int]]) -> dict:
    if len(runs) != 3:
        raise ValueError("exactly three runs are required")
    errors = sum(int(run["errors"]) for run in runs)
    timeouts = sum(int(run["timeouts"]) for run in runs)
    unexpected_5xx = sum(int(run.get("unexpected5xx", 0)) for run in runs)
    return {
        "runs": runs,
        "throughputMedian": float(statistics.median(float(run["throughput"]) for run in runs)),
        "p95MedianMs": float(statistics.median(float(run["p95Ms"]) for run in runs)),
        "p99MedianMs": float(statistics.median(float(run["p99Ms"]) for run in runs)),
        "errors": errors,
        "timeouts": timeouts,
        "unexpected5xx": unexpected_5xx,
        "passed": errors == 0 and timeouts == 0 and unexpected_5xx == 0,
    }


def parse_browser_coverage(output: str, expected_operations: set[str]) -> dict:
    public = re.findall(r"^PUBLIC_OPERATION_SUCCESS (\d+): ([A-Za-z0-9_,]+)$", output, re.MULTILINE)
    bypass = re.findall(r"^APPROVED_BYPASS_CALLS (\d+)$", output, re.MULTILINE)
    if len(public) != 1 or len(bypass) != 1:
        raise ValueError("strict browser coverage markers are missing or duplicated")
    count = int(public[0][0])
    operations = public[0][1].split(",")
    bypass_count = int(bypass[0])
    if count != 33 or set(operations) != expected_operations or bypass_count < 1:
        raise ValueError("strict browser coverage requires Public 33 and observed auth bypass calls")
    return {
        "classification": "PLAYWRIGHT_LOCATOR_REAL",
        "publicSuccessCount": count,
        "operationIds": sorted(operations),
        "approvedBypassOperationCount": 1,
        "actualBypassCallCount": bypass_count,
    }


def parse_internal_access_log(output: str, operations: list[Operation]) -> dict:
    evidence = []
    for method, path, status_text in re.findall(
        r'\b(GET|POST|PUT|PATCH|DELETE) (/internal/[^ ?]+)(?:\?[^ ]*)? HTTP/[^\"]+" (\d{3})', output
    ):
        operation = match_operation(operations, method, path)
        status = int(status_text)
        if operation and 200 <= status < 300:
            evidence.append({
                "operationId": operation.operation_id,
                "method": method,
                "path": path,
                "status": status,
                "observedVia": "ANALYSIS_ACCESS_LOG",
            })
    unique = {item["operationId"] for item in evidence}
    return {
        "classification": "REAL_ANALYSIS_ACCESS_LOG",
        "successCount": len(unique),
        "expected": len(operations),
        "evidence": evidence,
        "passed": len(unique) == len(operations),
    }


def judge_period_active(now: datetime | None = None) -> bool:
    current = (now or datetime.now(KST)).astimezone(KST)
    return JUDGE_START <= current <= JUDGE_END


def import_command_args(artifact_path: str) -> list[str]:
    return [
        "java",
        "-Dloader.main=com.dacon.core.policy.PolicyArtifactImportCommand",
        "-cp", "app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
        "--spring.main.web-application-type=none",
        f"--app.policy-artifact-path={artifact_path}",
    ]
