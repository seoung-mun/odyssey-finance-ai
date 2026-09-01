#!/usr/bin/env python3
"""실제 compose 스택에서 사용자 시나리오를 끝까지 태워 백엔드 blocker를 검증한다.

scripts/real-compose-qa.sh의 격리 패턴(랜덤 project명·랜덤 loopback 포트·일회용 secret·
Caddy local CA·소유 자원만 정리)을 그대로 쓰되, 이 스크립트는 목표 입력 → 선반영 →
재계획으로 이어지는 실제 사용자 흐름과 적대적 케이스를 실제 HTTPS API로 수행한다.
신규 의존성 없이 표준 라이브러리만 사용한다.

사용법: python3 scripts/real_scenario_qa.py
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "qa"))
from runner_support import (  # noqa: E402
    contract_operations,
    import_command_args,
    judge_period_active,
    match_operation,
    parse_browser_coverage,
    parse_internal_access_log,
    percentile,
    three_run_summary,
)

ROOT = Path(__file__).resolve().parent.parent
COMPOSE_FILE = ROOT / "docker-compose.yml"
KST = timezone(timedelta(hours=9))
PASSED: list[str] = []
PUBLIC_OPERATIONS = contract_operations(ROOT / "API/openapi-public.yaml")
INTERNAL_OPERATIONS = contract_operations(ROOT / "API/openapi-internal.yaml")


class ScenarioFailure(Exception):
    """단언 실패로 시나리오를 즉시 중단시킨다."""


def log_ok(name: str, detail: str = "") -> None:
    PASSED.append(name)
    suffix = f" — {detail}" if detail else ""
    print(f"[ ok ] {name}{suffix}")


def check(condition: bool, name: str, detail: str) -> None:
    if not condition:
        raise ScenarioFailure(f"{name} — {detail}")
    log_ok(name, detail)


def free_loopback_ports(count: int) -> list[int]:
    sockets = [socket.socket() for _ in range(count)]
    for sock in sockets:
        sock.bind(("127.0.0.1", 0))
    ports = [sock.getsockname()[1] for sock in sockets]
    for sock in sockets:
        sock.close()
    return ports


class Stack:
    """격리된 compose project 하나를 기동·정리한다."""

    def __init__(self) -> None:
        self.tmp_dir = Path(tempfile.mkdtemp(prefix="odyssey-scenario-qa."))
        self.env_file = self.tmp_dir / ".env"
        self.override_file = self.tmp_dir / "compose.qa.yml"
        self.ca_file = self.tmp_dir / "caddy-root.crt"
        self.project = f"odyssey-scenario-{secrets.token_hex(4)}-{int(time.time())}"
        self.http_port, self.https_port = free_loopback_ports(2)
        self.postgres_db = "dacon_scenario"
        self.postgres_user = "dacon_scenario"
        self.postgres_password = secrets.token_hex(24)
        self.redis_password = secrets.token_hex(24)
        self.internal_token = secrets.token_hex(24)
        self.jwt_secret = secrets.token_hex(32)
        self.e2e_token = secrets.token_hex(24)
        self.started = False
        self.https_base = f"https://localhost:{self.https_port}"
        self.opener: urllib.request.OpenerDirector | None = None
        self.core_log_file = self.tmp_dir / "core-api.log"
        self.core_log_proc: subprocess.Popen | None = None
        self.evidence: list[dict] = []

    # ── compose 실행 ────────────────────────────────────────────────
    def compose(
        self, *args: str, capture: bool = False, check_result: bool = True,
    ) -> subprocess.CompletedProcess:
        cmd = [
            "docker", "compose", "--project-name", self.project,
            "--env-file", str(self.env_file),
            "-f", str(COMPOSE_FILE), "-f", str(self.override_file),
            *args,
        ]
        return subprocess.run(cmd, check=check_result, capture_output=capture, text=True)

    def psql(self, sql: str) -> str:
        result = self.compose(
            "exec", "-T", "postgres", "psql", "-U", self.postgres_user,
            "-d", self.postgres_db, "-tAc", sql, capture=True,
        )
        return result.stdout.strip()

    def command(self, artifact: Path, expect_success: bool) -> subprocess.CompletedProcess:
        target = f"/tmp/{artifact.name}"
        self.compose("cp", str(artifact), f"core-api:{target}")
        return self.compose(
            "exec", "-T", "core-api", *import_command_args(target),
            capture=True, check_result=expect_success,
        )

    # ── 기동/정리 ────────────────────────────────────────────────────
    def setup_files(self) -> None:
        self.env_file.write_text(
            "\n".join([
                f"POSTGRES_DB={self.postgres_db}",
                f"POSTGRES_USER={self.postgres_user}",
                f"POSTGRES_PASSWORD={self.postgres_password}",
                f"REDIS_PASSWORD={self.redis_password}",
                f"INTERNAL_API_TOKEN={self.internal_token}",
                f"JWT_SECRET={self.jwt_secret}",
                "GOOGLE_CLIENT_ID=scenario-qa-invalid.apps.googleusercontent.com",
                f"API_ADDRESS=localhost:{self.https_port}",
                f"HTTP_ADDRESS=localhost:{self.http_port}",
                f"HTTP_PORT={self.http_port}",
                f"HTTPS_PORT={self.https_port}",
                "LOG_LEVEL=INFO",
                # e2e 프로필: docker-compose.yml의 core-api env는 기본이 빈 문자열이라
                # 이 project 밖에서는 아무 영향이 없다. E2eAuthController는 이 프로필과
                # 토큰이 모두 있어야만 활성화된다.
                "SPRING_PROFILES_ACTIVE=e2e",
                f"E2E_TOKEN={self.e2e_token}",
            ]) + "\n"
        )
        self.env_file.chmod(0o600)
        self.override_file.write_text(
            "services:\n"
            "  caddy:\n"
            "    ports: !override\n"
            '      - "127.0.0.1:${HTTP_PORT}:${HTTP_PORT}"\n'
            '      - "127.0.0.1:${HTTPS_PORT}:${HTTPS_PORT}"\n'
            '      - "127.0.0.1:${HTTPS_PORT}:${HTTPS_PORT}/udp"\n'
            "    healthcheck:\n"
            '      test: ["CMD", "wget", "--no-check-certificate", "--spider",'
            ' "https://localhost:${HTTPS_PORT}/actuator/health"]\n'
        )

    def audit_ports(self) -> None:
        """caddy 외 서비스가 host 포트를 publish하지 않는지, publish된 포트가 전부
        loopback인지 실제 compose config로 감사한다(real-compose-qa.sh와 동일 기준)."""
        result = self.compose("config", "--format", "json", capture=True)
        services = json.loads(result.stdout)["services"]
        for name, service in services.items():
            ports = service.get("ports", [])
            if name != "caddy" and ports:
                raise ScenarioFailure(f"non-caddy service publishes ports: {name}")
            for port in ports:
                host_ip = port.get("host_ip", "") if isinstance(port, dict) else ""
                if host_ip != "127.0.0.1":
                    raise ScenarioFailure(f"non-loopback published port: {name}")
        log_ok("포트 감사", "caddy만 loopback HTTP/HTTPS를 publish함")

    def up(self) -> None:
        self.compose("build", "--no-cache")
        self.started = True
        self.compose("up", "-d", "--wait", "--wait-timeout", "300")
        # 실패 시점에만 docker logs를 조회하면 컨테이너 stdout 버퍼링 때문에 실제 실패
        # 요청의 로그가 아직 안 나온 스냅샷을 잡을 수 있다. 기동 직후부터 계속 스트리밍해
        # 파일에 쌓아 두고, 실패하면 그 파일을 그대로 읽는다.
        self.core_log_proc = subprocess.Popen(
            [
                "docker", "compose", "--project-name", self.project,
                "--env-file", str(self.env_file),
                "-f", str(COMPOSE_FILE), "-f", str(self.override_file),
                "logs", "-f", "--no-color", "core-api",
            ],
            stdout=self.core_log_file.open("wb"),
            stderr=subprocess.STDOUT,
        )

    def fetch_ca(self) -> None:
        self.compose(
            "cp", "caddy:/data/caddy/pki/authorities/local/root.crt", str(self.ca_file)
        )
        self.ca_file.chmod(0o600)
        context = ssl.create_default_context(cafile=str(self.ca_file))
        self.opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context))

    def wait_https(self, timeout: float = 60.0) -> None:
        assert self.opener is not None
        deadline = time.time() + timeout
        last_error: Exception | None = None
        while time.time() < deadline:
            try:
                self.opener.open(f"{self.https_base}/actuator/health", timeout=5).read()
                return
            except Exception as error:  # noqa: BLE001 — 준비될 때까지 재시도
                last_error = error
                time.sleep(1)
        raise ScenarioFailure(f"Caddy HTTPS가 준비되지 않았습니다: {last_error}")

    def cleanup(self) -> None:
        if self.core_log_proc is not None:
            self.core_log_proc.terminate()
            try:
                self.core_log_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.core_log_proc.kill()
        if self.started:
            try:
                self.compose("down", "-v", "--remove-orphans")
            except Exception as error:  # noqa: BLE001 — 정리는 최선을 다하고 계속한다
                print(f"경고: compose down 실패: {error}", file=sys.stderr)
            for image in ("core-api", "analysis-api", "caddy"):
                subprocess.run(
                    ["docker", "image", "rm", f"{self.project}-{image}"],
                    capture_output=True, check=False,
                )
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    # ── HTTP 호출 ────────────────────────────────────────────────────
    def call(
        self, method: str, path: str, token: str | None = None,
        body: object | None = None, params: dict[str, str] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, object | None, dict]:
        assert self.opener is not None
        url = f"{self.https_base}{path}"
        if params:
            query = "&".join(
                f"{key}={urllib.request.quote(str(value))}"
                for key, value in params.items() if value is not None
            )
            if query:
                url = f"{url}?{query}"
        data = None
        headers = dict(extra_headers or {})
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        started = time.perf_counter()
        try:
            response = self.opener.open(request, timeout=20)
            raw = response.read()
            status = response.status
            resp_headers = dict(response.headers.items())
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
            resp_headers = dict(error.headers.items())
        latency_ms = (time.perf_counter() - started) * 1000
        parsed = json.loads(raw) if raw else None
        operation = match_operation(PUBLIC_OPERATIONS, method, path)
        bypass = path.split("?", 1)[0] == "/api/v1/auth/e2e"
        self.evidence.append({
            "operationId": None if bypass else operation.operation_id if operation else None,
            "method": method,
            "path": path.split("?", 1)[0],
            "status": status,
            "latencyMs": round(latency_ms, 3),
            "observedVia": "APPROVED_BYPASS" if bypass else "HTTPS_CADDY_CORE",
        })
        return status, parsed, resp_headers


def iso_kst(dt: datetime) -> str:
    return dt.astimezone(KST).isoformat(timespec="seconds")


def canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def qa_policy_artifact(destination: Path) -> Path:
    """공식 PENDING 파일을 바꾸지 않고 검색 24건 + 합성 계산정책 QA fixture를 만든다."""
    candidate = json.loads((ROOT / "data/policy/policy-artifact-candidate.json").read_text())
    candidate["artifactVersion"] = f"qa-unapproved-data-{secrets.token_hex(8)}"
    candidate["reviewGate"] = {
        "status": "APPROVED", "importable": True,
        "reason": "UNAPPROVED_DATA_QA_FIXTURE; real 24 sources, synthetic QA embeddings",
    }
    for policy in candidate["policies"]:
        policy["version"]["reviewStatus"] = "APPROVED"
        policy["version"]["calculationMode"] = "INFORMATIONAL"
        policy["version"]["calculationRule"] = None
        for chunk in policy["version"]["chunks"]:
            chunk["embedding"] = [1] + [0] * 1023
    for profile in candidate["queryProfiles"]:
        profile["embedding"] = [1] + [0] * 1023

    source = dict(candidate["sources"][0])
    source.update({
        "sourceKey": "qa-synthetic-calculable-source",
        "organization": "QA synthetic institution",
        "officialUrl": "https://example.invalid/qa-synthetic-policy",
        "finalUrl": "https://example.invalid/qa-synthetic-policy",
        "bodyMarkers": ["SYNTHETIC_QA_ONLY"],
    })
    candidate["sources"].append(source)
    synthetic = json.loads(json.dumps(candidate["policies"][0]))
    synthetic.update({
        "policyKey": "qa-synthetic-calculable-policy",
        "title": "SYNTHETIC QA 계산 정책",
        "supportGoal": "DORMITORY",
        "summary": "QA 전용 합성 정책이며 실제 지원 정책이 아니다.",
        "planConnection": "QA_ONLY",
    })
    version = synthetic["version"]
    synthetic_locator_hash = hashlib.sha256(b"SYNTHETIC_QA_ONLY").hexdigest()
    version.update({
        "sourceKey": source["sourceKey"],
        "sourceVersion": "QA_ONLY_V1",
        "sourceLocator": "SYNTHETIC_QA_ONLY",
        "locatorSha256": synthetic_locator_hash,
        "reviewStatus": "APPROVED",
        "calculationMode": "ONE_TIME_FUNDING",
        "calculationRule": {
            "adjustmentType": "ONE_TIME_FUNDING",
            "amountUpperBound": 1000000,
            "maxMonths": None,
            "sourceVersion": "QA_ONLY_V1",
            "approvedLocator": "SYNTHETIC_QA_ONLY",
            "approvedSha256": synthetic_locator_hash,
            "goldenCase": {"label": "SYNTHETIC_QA_ONLY", "amountWon": 100000},
            "humanApprovedAt": "2026-09-01T00:00:00Z",
            "reviewer": "task7-qa-runner",
        },
    })
    candidate["policies"].append(synthetic)
    candidate["manifestSha256"] = ""
    candidate["manifestSha256"] = hashlib.sha256(canonical_json(candidate)).hexdigest()
    destination.write_bytes(canonical_json(candidate))
    return destination


def import_policy_fixtures(stack: Stack) -> None:
    before = stack.psql("SELECT count(*) FROM policy_index_snapshots")
    pending = stack.command(ROOT / "data/policy/policy-artifact-candidate.json", expect_success=False)
    after = stack.psql("SELECT count(*) FROM policy_index_snapshots")
    check(
        pending.returncode != 0 and before == after,
        "공식 PENDING artifact command 거부·write 0",
        f"returncode={pending.returncode} snapshots={before}->{after}",
    )
    fixture = qa_policy_artifact(stack.tmp_dir / "UNAPPROVED_DATA_QA_FIXTURE.json")
    first = stack.command(fixture, expect_success=True)
    second = stack.command(fixture, expect_success=True)
    snapshot_count = stack.psql(
        "SELECT count(*) FROM policy_index_snapshots WHERE artifact_version LIKE 'qa-unapproved-data-%'"
    )
    check(
        first.returncode == second.returncode == 0 and snapshot_count == "1",
        "QA fixture 비공개 command same-snapshot 멱등 적재",
        "label=UNAPPROVED_DATA_QA_FIXTURE synthetic=SYNTHETIC_QA_ONLY snapshots=1",
    )


def policy_requests(stack: Stack, token: str, goal_id: int) -> tuple[dict, dict]:
    answers: list[dict] = []
    while True:
        status, body, _ = stack.call(
            "POST", "/api/v1/policies/search", token=token,
            body={"supportGoal": "DORMITORY", "answers": answers},
        )
        check(status == 200, "QA fixture 정책 검색 200", f"status={status}")
        if body["type"] == "RESULTS":
            break
        question = body["question"]
        answers.append({
            "questionId": question["questionId"],
            "value": question["options"][0]["value"],
        })
    result = next(
        item for item in body["results"] if item["title"] == "SYNTHETIC QA 계산 정책"
    )
    plan_id = int(stack.psql(
        f"SELECT id FROM plan_versions WHERE goal_id={goal_id} AND status='ACTIVE' ORDER BY id DESC LIMIT 1"
    ))
    scenario = {
        "currentPlanVersionId": plan_id,
        "supportGoal": "DORMITORY",
        "answers": answers,
        "confirmedAward": {
            "type": "ONE_TIME_FUNDING", "institutionConfirmed": True,
            "amountWon": 100000, "startYearMonth": datetime.now(KST).strftime("%Y-%m"),
        },
    }
    before = stack.psql(
        "SELECT concat_ws('|',(SELECT count(*) FROM plan_versions),"
        "(SELECT count(*) FROM transactions),(SELECT count(*) FROM scheduled_expenses),"
        "(SELECT count(*) FROM replan_events))"
    )
    status, _, _ = stack.call(
        "POST", f"/api/v1/policy-versions/{result['policyVersionId']}/scenario",
        token=token, body=scenario,
    )
    after = stack.psql(
        "SELECT concat_ws('|',(SELECT count(*) FROM plan_versions),"
        "(SELECT count(*) FROM transactions),(SELECT count(*) FROM scheduled_expenses),"
        "(SELECT count(*) FROM replan_events))"
    )
    check(
        status == 200 and before == after,
        "SYNTHETIC_QA_ONLY 정책 비교·제품 상태 불변",
        f"status={status} fingerprint={before}->{after}",
    )
    return {"supportGoal": "DORMITORY", "answers": answers}, {
        "path": f"/api/v1/policy-versions/{result['policyVersionId']}/scenario", "body": scenario,
    }


def request_batch(
    stack: Stack, token: str, request: dict, concurrency: int, total: int = 100,
) -> dict:
    def invoke(_: int) -> tuple[int, float, bool]:
        started = time.perf_counter()
        try:
            status, _, _ = stack.call(
                "POST", request["path"], token=token, body=request["body"],
            )
            return status, (time.perf_counter() - started) * 1000, False
        except (TimeoutError, urllib.error.URLError):
            return 0, (time.perf_counter() - started) * 1000, True

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(invoke, range(total)))
    elapsed = time.perf_counter() - started
    return {
        "concurrency": concurrency,
        "total": total,
        "throughput": total / elapsed,
        "errors": sum(status == 0 or status >= 400 for status, _, _ in results),
        "timeouts": sum(timed_out for _, _, timed_out in results),
        "unexpected5xx": sum(status >= 500 for status, _, _ in results),
        "p95Ms": percentile([latency for _, latency, _ in results], 0.95),
        "p99Ms": percentile([latency for _, latency, _ in results], 0.99),
    }


def run_performance(stack: Stack, token: str, search: dict, scenario: dict) -> dict:
    workloads = {
        "search": {"path": "/api/v1/policies/search", "body": search},
        "scenario": scenario,
    }
    report: dict[str, object] = {
        "classification": "REAL_APPLIED_ABSOLUTE_ONLY",
        "baselineStatus": "UNRESOLVED_UNAPPLIED_BASELINE",
    }
    for name, request in workloads.items():
        warm = three_run_summary([request_batch(stack, token, request, 1, 10) for _ in range(3)])
        c4 = three_run_summary([request_batch(stack, token, request, 4) for _ in range(3)])
        c8 = three_run_summary([request_batch(stack, token, request, 8) for _ in range(3)])
        report[name] = {
            "warm": warm,
            "warmPassed": warm["passed"] and warm["p95MedianMs"] <= 1000
            and warm["p99MedianMs"] <= 2000,
            "c4": c4,
            "c8": c8,
            "c8Passed": c8["passed"],
        }
        report[name]["passed"] = (
            report[name]["warmPassed"] and c4["passed"] and report[name]["c8Passed"]
        )
    report["absoluteChecksPassed"] = all(report[name]["passed"] for name in workloads)
    report["passed"] = False
    return report


def container_snapshot(stack: Stack) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for service in ("core-api", "analysis-api", "postgres", "redis", "caddy"):
        container_id = stack.compose("ps", "-q", service, capture=True).stdout.strip()
        inspected = subprocess.run(
            ["docker", "inspect", "--format", "{{json .}}", container_id],
            check=True, capture_output=True, text=True,
        )
        state = json.loads(inspected.stdout)
        stats = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", container_id],
            check=True, capture_output=True, text=True,
        ).stdout.split("/", 1)[0].strip()
        units = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}
        match = __import__("re").fullmatch(r"([0-9.]+)([KMG]?i?B)", stats)
        if not match:
            raise ScenarioFailure(f"RSS parse 실패: {service}={stats}")
        result[service] = {
            "restartCount": int(state["RestartCount"]),
            "oomKilled": bool(state["State"]["OOMKilled"]),
            "rssBytes": int(float(match.group(1)) * units[match.group(2)]),
        }
    return result


def run_soak(
    stack: Stack, token: str, search: dict, scenario: dict, minutes: int,
) -> dict:
    warm = container_snapshot(stack)
    samples: list[dict] = []
    deadline = time.monotonic() + minutes * 60
    requests = (
        {"path": "/api/v1/policies/search", "body": search}, scenario,
    )
    index = 0
    while time.monotonic() < deadline:
        batch = request_batch(stack, token, requests[index % 2], 4, 20)
        if batch["errors"] or batch["timeouts"]:
            raise ScenarioFailure("soak batch에 HTTP 오류 또는 timeout이 있습니다")
        snapshot = container_snapshot(stack)
        samples.append({"at": datetime.now(KST).isoformat(), "batch": batch, "containers": snapshot})
        index += 1
        time.sleep(min(10, max(0, deadline - time.monotonic())))
    final_cutoff = datetime.now(KST) - timedelta(minutes=min(10, minutes))
    final_samples = [sample for sample in samples if datetime.fromisoformat(sample["at"]) >= final_cutoff]
    if not samples or not final_samples:
        raise ScenarioFailure("soak sample 또는 final 10m sample이 비었습니다")
    services = {}
    for service, warm_value in warm.items():
        rss = [sample["containers"][service]["rssBytes"] for sample in final_samples]
        rss_p95 = percentile(rss, 0.95)
        restarts = max(
            warm_value["restartCount"],
            *(sample["containers"][service]["restartCount"] for sample in samples),
        )
        oom_killed = warm_value["oomKilled"] or any(
            sample["containers"][service]["oomKilled"] for sample in samples
        )
        services[service] = {
            "warmRssBytes": warm_value["rssBytes"],
            "final10mRssP95Bytes": rss_p95,
            "restartCount": restarts,
            "oomKilled": oom_killed,
            "passed": restarts == 0 and not oom_killed
            and rss_p95 <= warm_value["rssBytes"] * 1.20,
        }
    check(
        all(service["passed"] for service in services.values()),
        "soak restart/OOM/RSS 기준",
        f"minutes={minutes} services={services}",
    )
    return {
        "classification": "REAL_SOAK_30M_OR_LONGER",
        "minutes": minutes, "samples": len(samples), "services": services,
    }


def run_browser_scenario(stack: Stack, retry_username: str) -> dict:
    if shutil.which("npm") is None or shutil.which("openssl") is None:
        raise ScenarioFailure("REAL 브라우저 QA에는 npm과 openssl이 필요합니다")
    context = ssl.create_default_context(cafile=str(stack.ca_file))
    with socket.create_connection(("localhost", stack.https_port), timeout=10) as connection:
        with context.wrap_socket(connection, server_hostname="localhost") as tls:
            leaf_certificate = tls.getpeercert(binary_form=True)
    public_key = subprocess.run(
        ["openssl", "x509", "-inform", "DER", "-pubkey", "-noout"],
        input=leaf_certificate,
        check=True,
        capture_output=True,
    ).stdout
    public_key_der = subprocess.run(
        ["openssl", "pkey", "-pubin", "-outform", "DER"],
        input=public_key,
        check=True,
        capture_output=True,
    ).stdout
    env = os.environ.copy()
    env.update({
        "E2E_BASE_URL": stack.https_base,
        "E2E_TLS_SPKI": base64.b64encode(hashlib.sha256(public_key_der).digest()).decode(),
        "E2E_TOKEN": stack.e2e_token,
        "E2E_RETRY_USERNAME": retry_username,
    })
    spec = ROOT / "web/e2e-real/public-operations.spec.ts"
    source = spec.read_text(encoding="utf-8")
    forbidden = {
        "routeInterception": len(__import__("re").findall(r"page\.route|route\.fulfill", source)),
        "evaluateFetch": len(__import__("re").findall(r"page\.evaluate[\s\S]{0,300}?fetch\(", source)),
    }
    if any(forbidden.values()):
        raise ScenarioFailure(f"Web REAL spec 금지 기법 발견: {forbidden}")
    completed = subprocess.run(
        ["npm", "run", "test:e2e:real"],
        cwd=ROOT / "web",
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    expected = {operation.operation_id for operation in PUBLIC_OPERATIONS}
    expected.remove("exchangeGoogleToken")
    coverage = parse_browser_coverage(completed.stdout, expected)
    log_ok("route interception 없는 실제 HTTPS Playwright")
    return coverage


def run_demo_scenario(stack: Stack, other_token: str) -> None:
    call = stack.call
    e2e_headers = {"X-E2E-Token": stack.e2e_token}

    status, _, _ = call("GET", "/api/v1/demo/testers")
    check(status == 401, "데모 테스터 무인증 조회 거부", f"status={status}")
    status, testers, _ = call("GET", "/api/v1/demo/testers", token=other_token)
    check(
        status == 200 and isinstance(testers, list),
        "데모 테스터 응답 형식", f"status={status} type={type(testers).__name__}",
    )
    check(
        len(testers) == 3 and all(isinstance(tester, dict) and tester.get("testerId") for tester in testers)
        and len({tester["testerId"] for tester in testers}) == 3,
        "데모 테스터 3명 조회", f"testers={len(testers)}",
    )
    status, other_me, _ = call("GET", "/api/v1/me", token=other_token)
    other_user_id = other_me["userId"]
    other_snapshot_sql = (
        "SELECT string_agg(kind || ':' || payload, E'\\n' ORDER BY kind, payload) FROM ("
        f"SELECT 'user_profiles' kind, row_to_json(p)::text payload FROM user_profiles p WHERE user_id={other_user_id} UNION ALL "
        f"SELECT 'financial_profiles', row_to_json(p)::text FROM financial_profiles p WHERE user_id={other_user_id} UNION ALL "
        f"SELECT 'financial_goals', row_to_json(g)::text FROM financial_goals g WHERE user_id={other_user_id} UNION ALL "
        f"SELECT 'scheduled_expenses', row_to_json(e)::text FROM scheduled_expenses e WHERE user_id={other_user_id} UNION ALL "
        f"SELECT 'transactions', row_to_json(t)::text FROM transactions t WHERE user_id={other_user_id}"
        ") snapshot"
    )

    for tester_index, tester in enumerate(testers):
        tester_id = tester["testerId"]
        status, body, _ = call(
            "POST", "/api/v1/auth/e2e",
            params={"username": f"scenario-demo-{tester_id}-{secrets.token_hex(4)}"},
            extra_headers=e2e_headers,
        )
        check(status == 200, f"{tester_id}: 데모 사용자 로그인", f"status={status}")
        demo_token = body["accessToken"]

        if tester_index == 0:
            def seed() -> tuple[int, object | None, dict]:
                return call(
                    "POST", "/api/v1/me/demo-seed", token=demo_token,
                    body={"testerId": tester_id},
                )

            with ThreadPoolExecutor(max_workers=2) as pool:
                seed_results = [future.result() for future in (pool.submit(seed), pool.submit(seed))]
            status, seeded, _ = seed_results[0]
            check(
                all(result[0] == 200 for result in seed_results)
                and seed_results[0][1] == seed_results[1][1],
                f"{tester_id}: 동일 사용자 동시 seed 직렬화",
                f"statuses={[result[0] for result in seed_results]}",
            )
        else:
            status, seeded, _ = call(
                "POST", "/api/v1/me/demo-seed", token=demo_token,
                body={"testerId": tester_id},
            )
        check(
            status == 200 and seeded["testerId"] == tester_id,
            f"{tester_id}: 데모 데이터 seed", f"status={status} body={seeded}",
        )
        status, me, _ = call("GET", "/api/v1/me", token=demo_token)
        demo_user_id = me["userId"]
        goal_id = me["activeGoalId"]
        check(
            status == 200 and me["onboardingComplete"] is True and isinstance(goal_id, int),
            f"{tester_id}: seed 후 activeGoalId 조회", f"status={status} goalId={goal_id}",
        )
        if tester_index == 0:
            concurrent_counts = stack.psql(
                "SELECT concat_ws('|',"
                f" (SELECT count(*) FROM demo_seed_state WHERE user_id={demo_user_id}),"
                f" (SELECT count(*) FROM user_profiles WHERE user_id={demo_user_id}),"
                f" (SELECT count(*) FROM financial_profiles WHERE user_id={demo_user_id}),"
                f" (SELECT count(*) FROM financial_goals WHERE user_id={demo_user_id}),"
                " (SELECT count(*) FROM (SELECT external_transaction_id FROM transactions"
                f" WHERE user_id={demo_user_id} GROUP BY external_transaction_id HAVING count(*) > 1) duplicates))"
            )
            check(
                concurrent_counts == "1|1|1|1|0",
                f"{tester_id}: 동시 seed 뒤 사용자 상태·거래 ID 유일성",
                f"state|profile|financial|goal|duplicates={concurrent_counts}",
            )

        transaction_hash_before = hashlib.sha256(stack.psql(
            "SELECT string_agg(concat_ws('|', transaction_at, amount, category,"
            " merchant_name, external_transaction_id), ',' ORDER BY external_transaction_id)"
            f" FROM transactions WHERE user_id={demo_user_id}"
        ).encode()).hexdigest()
        seed_state_before = stack.psql(
            "SELECT concat_ws('|', tester_id, scenario_version, seeded_on, seeded_at)"
            f" FROM demo_seed_state WHERE user_id={demo_user_id}"
        )
        plan_count_before = stack.psql(
            "SELECT count(*) FROM plan_versions pv JOIN financial_goals g ON g.id=pv.goal_id"
            f" WHERE g.user_id={demo_user_id}"
        )
        check(plan_count_before == "0", f"{tester_id}: seed는 계획을 만들지 않음", f"plans={plan_count_before}")

        status, plan, _ = call(
            "POST", f"/api/v1/goals/{goal_id}/plan-versions", token=demo_token,
            body={"generationType": "INITIAL"},
        )
        options = plan.get("options", []) if isinstance(plan, dict) else []
        rates = [option["requiredReductionRate"] for option in options]
        check(status == 200 and len(options) == 3, f"{tester_id}: 초기 계획 옵션 3개", f"status={status} options={len(options)}")
        check(
            all(0.05 <= rate <= 0.30 for rate in rates) and len(set(rates)) == 3,
            f"{tester_id}: 옵션 절감률 범위·고유성", f"rates={rates}",
        )
        check(
            not all(option["aggressiveWarning"] for option in options),
            f"{tester_id}: 모든 옵션이 공격적 경고인 상태 금지",
            f"warnings={[option['aggressiveWarning'] for option in options]}",
        )

        plan_id = plan["id"]
        option_id = options[0]["id"]
        status, selected, _ = call(
            "POST", f"/api/v1/plan-versions/{plan_id}/select-option", token=demo_token,
            body={"planOptionId": option_id},
        )
        check(status == 200 and selected["status"] == "ACTIVE", f"{tester_id}: 옵션 선택", f"status={status}")
        status, dashboard, _ = call("GET", "/api/v1/dashboard", token=demo_token)
        check(
            status == 200 and dashboard["activePlan"]["id"] == plan_id
            and dashboard["selectedOption"]["id"] == option_id,
            f"{tester_id}: dashboard 선택 반영", f"status={status} planId={plan_id}",
        )

        other_snapshot_before = stack.psql(other_snapshot_sql)
        status, reseeded, _ = call(
            "POST", "/api/v1/me/demo-seed", token=demo_token,
            body={"testerId": tester_id},
        )
        check(status == 200, f"{tester_id}: 동일 테스터 reseed", f"status={status}")

        transaction_hash_after = hashlib.sha256(stack.psql(
            "SELECT string_agg(concat_ws('|', transaction_at, amount, category,"
            " merchant_name, external_transaction_id), ',' ORDER BY external_transaction_id)"
            f" FROM transactions WHERE user_id={demo_user_id}"
        ).encode()).hexdigest()
        seed_state_after = stack.psql(
            "SELECT concat_ws('|', tester_id, scenario_version, seeded_on, seeded_at)"
            f" FROM demo_seed_state WHERE user_id={demo_user_id}"
        )
        plan_count_after = stack.psql(
            "SELECT count(*) FROM plan_versions pv JOIN financial_goals g ON g.id=pv.goal_id"
            f" WHERE g.user_id={demo_user_id}"
        )
        check(plan_count_after == "0", f"{tester_id}: reseed가 기존 계획을 0개로 초기화", f"plans={plan_count_after}")
        check(
            transaction_hash_after == transaction_hash_before
            and seed_state_after == seed_state_before and reseeded == seeded,
            f"{tester_id}: reseed 거래 SHA·seed 상태·API 응답 결정성", f"sha={transaction_hash_after}",
        )
        status, dashboard, _ = call("GET", "/api/v1/dashboard", token=demo_token)
        check(
            status == 200 and dashboard["activePlan"] is None and dashboard["selectedOption"] is None,
            f"{tester_id}: reseed 후 dashboard 계획 초기화", f"status={status}",
        )
        check(
            stack.psql(other_snapshot_sql) == other_snapshot_before,
            f"{tester_id}: reseed가 다른 사용자 핵심 상태를 변경하지 않음", f"userId={other_user_id}",
        )

    status, body, _ = call(
        "POST", "/api/v1/me/demo-seed", token=other_token,
        body={"testerId": testers[0]["testerId"]},
    )
    check(
        status == 409 and body.get("code") == "DEMO_SEED_CONFLICT",
        "기존 비데모 사용자 seed 충돌", f"status={status} body={body}",
    )


def run_scenario(stack: Stack) -> None:
    call = stack.call

    # ── 사용자 A: 인증·온보딩 ───────────────────────────────────────
    e2e_headers = {"X-E2E-Token": stack.e2e_token}
    username_a = f"scenario-a-{secrets.token_hex(4)}"
    status, body, headers = call(
        "POST", "/api/v1/auth/e2e", params={"username": username_a},
        extra_headers=e2e_headers,
    )
    check(status == 200 and body.get("accessToken"), "A 로그인", f"status={status}")
    token_a = body["accessToken"]
    cookie = headers.get("Set-Cookie", "")
    check(
        "Secure" in cookie and "HttpOnly" in cookie and "SameSite=Strict" in cookie
        and "Path=/api/v1/auth" in cookie,
        "A refresh cookie 속성", "Secure,HttpOnly,SameSite,Path",
    )

    status, body, _ = call("GET", "/api/v1/me", token=token_a)
    check(status == 200 and body["onboardingComplete"] is False, "A 온보딩 전", f"{body}")

    status, _, _ = call(
        "PUT", "/api/v1/me/profile", token=token_a,
        body={"birthDate": "1995-05-15", "regionCode": "11680"},
    )
    check(status == 200, "A 인적 프로필 적재", f"status={status}")
    status, _, _ = call(
        "PUT", "/api/v1/me/financial-profile", token=token_a,
        body={"monthlyIncome": 4_200_000, "monthlyFixedCost": 1_650_000},
    )
    check(status == 200, "A 금융 프로필 적재", f"status={status}")
    bootstrap_now = datetime.now(KST)
    bootstrap_transactions = []
    for months_ago in range(12, 0, -1):
        absolute_month = bootstrap_now.year * 12 + bootstrap_now.month - 1 - months_ago
        year, zero_based_month = divmod(absolute_month, 12)
        for index, (amount, category) in enumerate(
            ((820_000, "식비"), (360_000, "교통"), (240_000, "생활"))
        ):
            occurred = datetime(year, zero_based_month + 1, 5 + index * 8, 12, tzinfo=KST)
            bootstrap_transactions.append(
                {
                    "transactionAt": iso_kst(occurred),
                    "amount": amount + (months_ago % 3) * 20_000,
                    "transactionType": "PAYMENT",
                    "category": category,
                    "sourceId": "SCEN",
                    "externalTransactionId": f"bootstrap-{months_ago}-{index}",
                }
            )
    status, body, _ = call(
        "POST", "/api/v1/transactions/import", token=token_a,
        body={"transactions": bootstrap_transactions},
    )
    check(status == 200 and body["inserted"] == 36, "A 과거 거래 적재", f"{body}")
    status, body, _ = call(
        "POST", "/api/v1/goals", token=token_a,
        body={
            "name": "비상금 2천만원",
            "targetAmount": 20_000_000,
            "currentSavedAmount": 5_000_000,
            "targetDate": (bootstrap_now + timedelta(days=548)).date().isoformat(),
        },
    )
    check(status == 201 and body.get("id"), "A 목표 적재", f"status={status}")

    status, body, _ = call("GET", "/api/v1/me", token=token_a)
    check(status == 200 and body["onboardingComplete"] is True, "A 온보딩 완료", f"{body}")
    goal_id = body["activeGoalId"]
    check(isinstance(goal_id, int), "목표 ID 확보", f"goalId={goal_id}")

    # ── 거래 조회: 무필터·단일필터·복합필터 (구 500 회귀 지점) ─────────
    status, body, _ = call("GET", "/api/v1/transactions", token=token_a, params={"limit": "50"})
    check(status == 200 and len(body["items"]) > 0, "거래 무필터 조회", f"status={status} items={len(body.get('items', []))}")
    first_page = body

    status, body, _ = call(
        "GET", "/api/v1/transactions", token=token_a,
        params={"category": "식비", "limit": "10"},
    )
    check(status == 200, "거래 카테고리 필터 조회", f"status={status}")

    now = datetime.now(KST)
    status, body, _ = call(
        "GET", "/api/v1/transactions", token=token_a,
        params={
            "from": iso_kst(now - timedelta(days=400)),
            "to": iso_kst(now),
            "category": "교통",
            "limit": "5",
        },
    )
    check(status == 200, "거래 복합필터(기간+카테고리) 조회", f"status={status}")

    if first_page["nextCursor"]:
        status, body, _ = call(
            "GET", "/api/v1/transactions", token=token_a,
            params={"cursor": first_page["nextCursor"], "limit": "50"},
        )
        check(status == 200, "거래 커서 다음 페이지", f"status={status}")

    status, body, _ = call(
        "GET", "/api/v1/transactions/monthly-summary", token=token_a, params={"months": "12"}
    )
    check(status == 200 and len(body) > 0, "월별 소비 집계", f"months={len(body)}")

    status, body, _ = call(
        "GET", "/api/v1/transactions/category-summary", token=token_a, params={"months": "3"}
    )
    check(status == 200, "카테고리별 소비 집계", f"status={status}")

    # ── 환불: linked·pending·unmatched, 늦은 PAYMENT resolve ──────────
    txn_at = iso_kst(now - timedelta(days=10))
    import1 = {
        "transactions": [
            {
                "transactionAt": txn_at, "amount": 50000, "transactionType": "PAYMENT",
                "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "pay-linked",
            },
            {
                "transactionAt": txn_at, "amount": 20000, "transactionType": "REFUND",
                "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "refund-linked",
                "refundAllocations": [
                    {"paymentSourceId": "SCEN", "paymentExternalTransactionId": "pay-linked", "amount": 20000}
                ],
            },
            {
                "transactionAt": txn_at, "amount": 15000, "transactionType": "REFUND",
                "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "refund-pending",
                "refundAllocations": [
                    {"paymentSourceId": "SCEN", "paymentExternalTransactionId": "pay-late", "amount": 15000}
                ],
            },
            {
                "transactionAt": txn_at, "amount": 10000, "transactionType": "REFUND",
                "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "refund-unmatched",
            },
        ]
    }
    status, body, _ = call("POST", "/api/v1/transactions/import", token=token_a, body=import1)
    check(
        status == 200 and body == {"inserted": 4, "skipped": 0, "allocationsInserted": 2, "allocationsPending": 1},
        "환불 배분 import(linked/pending/unmatched)", f"{body}",
    )

    resolved_before = stack.psql(
        "SELECT count(*) FROM refund_allocations WHERE payment_transaction_id IS NOT NULL"
    )
    import2 = {
        "transactions": [
            {
                "transactionAt": txn_at, "amount": 15000, "transactionType": "PAYMENT",
                "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "pay-late",
            }
        ]
    }
    status, body, _ = call("POST", "/api/v1/transactions/import", token=token_a, body=import2)
    check(status == 200 and body["inserted"] == 1, "늦은 PAYMENT 적재", f"{body}")
    resolved_after = stack.psql(
        "SELECT count(*) FROM refund_allocations WHERE payment_transaction_id IS NOT NULL"
    )
    check(
        int(resolved_after) == int(resolved_before) + 1,
        "늦은 PAYMENT가 pending 환불을 resolve",
        f"{resolved_before} → {resolved_after}",
    )

    status, body, _ = call("POST", "/api/v1/transactions/import", token=token_a, body=import1)
    check(
        status == 200 and body["inserted"] == 0 and body["skipped"] == 4,
        "동일 import 재전송은 중복 원장을 만들지 않음", f"{body}",
    )

    # ── 계획 생성: option 3개, 전체 원자 저장 ─────────────────────────
    plan_count_before = stack.psql(
        f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}"
    )
    status, body, _ = call(
        "POST", f"/api/v1/goals/{goal_id}/plan-versions", token=token_a,
        body={"generationType": "INITIAL"},
    )
    check(
        status == 200 and len(body["options"]) == 3,
        "초기 계획 생성(옵션 3개)", f"status={status} options={len(body.get('options', [])) if isinstance(body, dict) else '?'}",
    )
    plan_id = body["id"]
    band_total = sum(len(option["percentileBands"]) for option in body["options"])
    check(band_total > 0, "모든 옵션에 분위수 밴드 저장됨", f"band_total={band_total}")

    sim_rows = stack.psql(f"SELECT count(*) FROM simulation_runs WHERE plan_version_id={plan_id}")
    opt_rows = stack.psql(f"SELECT count(*) FROM plan_options WHERE plan_version_id={plan_id}")
    band_rows = stack.psql(
        "SELECT count(*) FROM plan_option_percentile_bands b JOIN plan_options o"
        f" ON b.plan_option_id=o.id WHERE o.plan_version_id={plan_id}"
    )
    plan_count_after = stack.psql(f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}")
    check(
        int(sim_rows) == 1 and int(opt_rows) == 3 and int(band_rows) == band_total
        and int(plan_count_after) == int(plan_count_before) + 1,
        "계획·시뮬레이션·옵션·밴드가 한 transaction으로 저장됨",
        f"sim={sim_rows} opt={opt_rows} band={band_rows} plan={plan_count_before}->{plan_count_after}",
    )

    recommended = body["options"][0]["recommendedMonthlySpending"]
    status, body, _ = call(
        "POST", f"/api/v1/plan-versions/{plan_id}/custom-option", token=token_a,
        body={"monthlySpending": max(0, recommended - 10000)},
    )
    check(status == 200 and body.get("id"), "CUSTOM 옵션 계산·저장", f"status={status}")

    status, body, _ = call("GET", f"/api/v1/plan-versions/{plan_id}", token=token_a)
    preset_option_id = next(o["id"] for o in body["options"] if o["optionType"] == "PRESET")
    status, body, _ = call(
        "POST", f"/api/v1/plan-versions/{plan_id}/select-option", token=token_a,
        body={"planOptionId": preset_option_id},
    )
    check(status == 200 and body["status"] == "ACTIVE", "옵션 선택 → 계획 ACTIVE 전환", f"status={status} planStatus={body.get('status')}")

    status, body, _ = call("GET", "/api/v1/dashboard", token=token_a)
    check(
        status == 200 and body["activePlan"]["id"] == plan_id
        and body["selectedOption"]["id"] == preset_option_id,
        "대시보드가 활성 계획·선택 옵션을 반영", f"status={status}",
    )

    explanation_status = poll_explanation(stack, token_a, plan_id)
    check(
        explanation_status in ("READY", "FALLBACK"),
        "설명 생성이 READY 또는 FALLBACK로 수렴", f"status={explanation_status}",
    )

    # ── 선반영: 예정지출 생성·수정이 새 제안 계획을 선계산 ─────────────
    scheduled_date = (now + timedelta(days=60)).date().isoformat()
    status, body, _ = call(
        "POST", "/api/v1/scheduled-expenses", token=token_a,
        body={"name": "생일 선물", "amount": 300000, "scheduledDate": scheduled_date},
    )
    check(
        status == 201 and body.get("triggeredReplanEventId"),
        "예정지출 생성이 재계획을 선계산", f"status={status} body={body}",
    )
    expense_id = body["id"]
    event1 = body["triggeredReplanEventId"]

    status, body, _ = call("GET", "/api/v1/dashboard", token=token_a)
    check(
        status == 200 and body["pendingProposal"] is not None
        and body["pendingProposal"]["replanEventId"] == event1,
        "대시보드에 제안 계획 노출", f"pendingProposal={body.get('pendingProposal')}",
    )
    proposed_plan_1 = body["pendingProposal"]["planVersionId"]

    status, body, _ = call(
        "POST", f"/api/v1/replan-events/{event1}/decision", token=token_a,
        body={"decision": "ACCEPT_NEW_PLAN"},
    )
    check(status == 200 and body["userDecision"] == "ACCEPT_NEW_PLAN", "제안 수락", f"status={status}")

    status, body, _ = call("GET", f"/api/v1/plan-versions/{proposed_plan_1}", token=token_a)
    preset_option_id_2 = next(o["id"] for o in body["options"] if o["optionType"] == "PRESET")
    status, body, _ = call(
        "POST", f"/api/v1/plan-versions/{proposed_plan_1}/select-option", token=token_a,
        body={"planOptionId": preset_option_id_2},
    )
    check(status == 200 and body["status"] == "ACTIVE", "수락한 제안을 옵션 선택으로 활성화", f"status={status}")

    # ── 자동 재계획: 큰 거래 shock, 중복 트리거 없음 ──────────────────
    # KEEP_CURRENT_PLAN이 이달 남은 기간 동안 소비 기반(shock·drift) 재계획을 억제하므로
    # 그 결정보다 먼저 shock를 검증한다.
    events_before = stack.psql(
        f"SELECT count(*) FROM replan_events WHERE goal_id={goal_id} AND trigger_type='LARGE_UNEXPECTED_TRANSACTION'"
    )
    shock_import = {
        "transactions": [{
            "transactionAt": iso_kst(now), "amount": 5_000_000, "transactionType": "PAYMENT",
            "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "pay-shock",
        }]
    }
    status, body, _ = call("POST", "/api/v1/transactions/import", token=token_a, body=shock_import)
    check(status == 200 and body["inserted"] == 1, "충격 거래 적재", f"{body}")
    time.sleep(2)  # afterCommit 이벤트 리스너가 비동기로 실행될 여유
    events_after = stack.psql(
        f"SELECT count(*) FROM replan_events WHERE goal_id={goal_id} AND trigger_type='LARGE_UNEXPECTED_TRANSACTION'"
    )
    check(
        int(events_after) == int(events_before) + 1,
        "큰 거래가 자동 재계획 이벤트를 정확히 1건 생성",
        f"{events_before} → {events_after}",
    )

    # ── 예정지출 수정 → 재계획 → KEEP_CURRENT_PLAN ────────────────────
    status, body, _ = call(
        "PATCH", f"/api/v1/scheduled-expenses/{expense_id}", token=token_a,
        body={"amount": 450000},
    )
    check(
        status == 200 and body.get("triggeredReplanEventId"),
        "예정지출 수정이 재계획을 선계산", f"status={status} body={body}",
    )
    event2 = body["triggeredReplanEventId"]

    status, body, _ = call(
        "POST", f"/api/v1/replan-events/{event2}/decision", token=token_a,
        body={"decision": "KEEP_CURRENT_PLAN"},
    )
    check(status == 200 and body["userDecision"] == "KEEP_CURRENT_PLAN", "제안 거절", f"status={status}")

    status, body, _ = call("GET", f"/api/v1/goals/{goal_id}", token=token_a)
    check(
        status == 200 and body["spendingReplanSuppressedUntil"] is not None,
        "거절 뒤 소비 기반 재계획 억제 설정", f"suppressedUntil={body.get('spendingReplanSuppressedUntil')}",
    )

    status, body, _ = call("GET", "/api/v1/dashboard", token=token_a)
    active_plan_id = body["activePlan"]["id"]
    check(
        active_plan_id == proposed_plan_1,
        "거절 뒤 기존 ACTIVE 계획 유지", f"activePlan={active_plan_id} expected={proposed_plan_1}",
    )

    # ── 수동 infeasible 재계획: 422 PLAN_INFEASIBLE ───────────────────
    status, body, _ = call(
        "PUT", "/api/v1/me/financial-profile", token=token_a,
        body={"monthlyIncome": 100000, "monthlyFixedCost": 5_000_000},
    )
    check(
        status == 200 and body["replanOutcome"] == "INFEASIBLE",
        "가용예산을 음수로 만든 금융정보 변경은 즉시 INFEASIBLE 저장", f"status={status} outcome={body.get('replanOutcome')}",
    )

    plan_count_before_422 = stack.psql(f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}")
    status, body, headers = call("POST", f"/api/v1/goals/{goal_id}/replan", token=token_a)
    check(
        status == 422 and body.get("code") == "PLAN_INFEASIBLE",
        "수동 재계획이 OpenAPI대로 422 PLAN_INFEASIBLE 반환", f"status={status} body={body}",
    )
    plan_count_after_422 = stack.psql(f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}")
    check(
        int(plan_count_after_422) == int(plan_count_before_422) + 1,
        "422여도 INFEASIBLE 계획 행 자체는 append-only로 남음(부분행 아님)",
        f"{plan_count_before_422} → {plan_count_after_422}",
    )

    status, body, _ = call("GET", "/api/v1/dashboard", token=token_a)
    check(
        status == 200 and body["activePlan"]["id"] == active_plan_id,
        "infeasible 시도 뒤에도 기존 ACTIVE 계획 불변", f"activePlan={body['activePlan']['id']}",
    )

    # 이후 단계(적대적 케이스 포함)가 다시 가용예산 있는 상태로 계산을 시도할 수 있도록
    # 금융정보를 샘플 데이터 기본값으로 되돌린다. 되돌리지 않으면 이 뒤의 모든 계획 생성이
    # availableVariableBudget<0으로 Analysis 호출 전에 즉시 infeasible 처리되어, 예를 들어
    # "Analysis 다운 → 503" 적대적 케이스가 실제로 Analysis를 타지 못하고 오검출된다.
    status, body, _ = call(
        "PUT", "/api/v1/me/financial-profile", token=token_a,
        body={"monthlyIncome": 4_200_000, "monthlyFixedCost": 1_650_000},
    )
    check(status == 200, "금융정보를 가용예산 있는 상태로 복원", f"status={status} outcome={body.get('replanOutcome')}")

    # ── 입력 경계 ──────────────────────────────────────────────────
    status, body, _ = call(
        "POST", "/api/v1/goals", token=token_a,
        body={"name": "중복 목표", "targetAmount": 1000000, "currentSavedAmount": 0, "targetDate": "2099-01-01"},
    )
    check(status == 409, "ACTIVE 목표가 있으면 추가 생성 거부(DB 제약→409)", f"status={status}")

    status, body, _ = call(
        "POST", "/api/v1/goals", token=token_a,
        body={"name": "잘못된 날짜", "targetAmount": 1000000, "currentSavedAmount": 0, "targetDate": "2099-02-30"},
    )
    check(status == 400, "존재하지 않는 달력 날짜는 백엔드에서 400", f"status={status}")

    status, body, _ = call(
        "GET", "/api/v1/transactions", token=token_a, params={"limit": "201"}
    )
    check(status == 400, "limit 상한 초과는 400", f"status={status}")

    status, body, _ = call(
        "POST", "/api/v1/transactions/import", token=token_a,
        body={"transactions": [{
            "transactionAt": iso_kst(now), "amount": -100, "transactionType": "PAYMENT",
            "category": "쇼핑", "sourceId": "SCEN", "externalTransactionId": "neg",
        }]},
    )
    check(status == 400, "음수 금액 거래는 400", f"status={status}")

    # 순수 int64 최댓값(9,223,372,036,854,775,807)은 월별 집계 SQL VIEW의 SUM(...)::bigint를
    # 그 사용자의 다른 거래와 합산할 때 BIGINT 범위를 넘겨 22003으로 깨뜨린다는 걸 실제로
    # 검증해 잡아냈다. 재발 방지로 TransactionDtos.MAX_AMOUNT(10^15) 상한을 추가했으므로,
    # 상한을 넘는 값은 400으로 거부되고, 상한선 자체는 여전히 정상 적재·조회돼야 한다.
    status, body, _ = call(
        "POST", "/api/v1/transactions/import", token=token_a,
        body={"transactions": [{
            "transactionAt": iso_kst(now), "amount": 9_223_372_036_854_775_807,
            "transactionType": "PAYMENT", "category": "쇼핑",
            "sourceId": "SCEN", "externalTransactionId": "pay-toolarge",
        }]},
    )
    check(status == 400, "int64 최댓값(현실적 상한 초과)은 400", f"status={status}")

    # 상한선(10^15) 금액 자체는 여기서 확인하지 않는다 — 이 값의 PAYMENT는 그 자체로 이번 달
    # 평균 소비를 극단적으로 밀어올려 이후 모든 계획 계산을 진짜로 infeasible하게 만든다.
    # run_adversarial의 "Analysis 다운 → 503" 검증은 가용예산이 있는 상태에서 Analysis 호출
    # 자체가 실패하는지를 봐야 하므로, 상한선 금액 적재는 그 검증들이 끝난 뒤로 미룬다.

    # ── 사용자 B: IDOR ─────────────────────────────────────────────
    username_b = f"scenario-b-{secrets.token_hex(4)}"
    status, body, _ = call(
        "POST", "/api/v1/auth/e2e", params={"username": username_b},
        extra_headers=e2e_headers,
    )
    check(status == 200, "B 로그인", f"status={status}")
    token_b = body["accessToken"]

    for path in (
        f"/api/v1/goals/{goal_id}",
        f"/api/v1/plan-versions/{plan_id}",
        f"/api/v1/goals/{goal_id}/replan-events",
    ):
        status, body, _ = call("GET", path, token=token_b)
        check(status == 404, f"B가 A 자원 조회 거부: {path}", f"status={status}")

    status, body, _ = call(
        "PATCH", f"/api/v1/scheduled-expenses/{expense_id}", token=token_b,
        body={"amount": 1},
    )
    check(status == 404, "B가 A 예정지출 수정 거부", f"status={status}")

    status, body, _ = call(
        "POST", f"/api/v1/replan-events/{event2}/decision", token=token_b,
        body={"decision": "ACCEPT_NEW_PLAN"},
    )
    check(status == 404, "B가 A 재계획 이벤트 결정 거부", f"status={status}")

    return goal_id, expense_id, token_a, username_a


def poll_explanation(stack: Stack, token: str, plan_id: int, timeout: float = 60.0) -> str:
    deadline = time.time() + timeout
    last = "PENDING"
    while time.time() < deadline:
        status, body, _ = stack.call("GET", f"/api/v1/plan-versions/{plan_id}/explanation", token=token)
        if status != 200:
            time.sleep(2)
            continue
        last = body["status"]
        if last in ("READY", "FALLBACK", "FAILED"):
            return last
        time.sleep(2)
    return last


def run_adversarial(stack: Stack, goal_id: int, expense_id: int, token_a: str) -> None:
    call = stack.call
    now = datetime.now(KST)

    # ── Analysis 다운: 503 + 부분 저장 0건 ─────────────────────────
    plan_count_before = stack.psql(f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}")
    stack.compose("stop", "analysis-api")
    status, body, _ = call(
        "POST", f"/api/v1/goals/{goal_id}/plan-versions", token=token_a,
        body={"generationType": "USER_REQUESTED"},
    )
    check(
        status == 503 and body.get("code") == "CALCULATION_SERVICE_UNAVAILABLE",
        "Analysis 연결 거부는 503으로 정규화", f"status={status} body={body}",
    )
    plan_count_after = stack.psql(f"SELECT count(*) FROM plan_versions WHERE goal_id={goal_id}")
    check(
        plan_count_after == plan_count_before,
        "Analysis 장애 시 계획 관련 행 0건(부분 저장 없음)",
        f"{plan_count_before} → {plan_count_after}",
    )
    user_id = stack.psql(f"SELECT user_id FROM financial_goals WHERE id={goal_id}")
    shock_inputs = stack.psql(
        "SELECT concat_ws('|', selected.recommended_monthly_spending, sample.p95) FROM"
        " (SELECT po.recommended_monthly_spending FROM plan_options po"
        " JOIN plan_versions pv ON pv.id=po.plan_version_id"
        f" WHERE pv.goal_id={goal_id} AND pv.status='ACTIVE' AND po.selected_at IS NOT NULL) selected,"
        " (SELECT percentile_disc(0.95) WITHIN GROUP (ORDER BY amount) AS p95 FROM"
        " (SELECT amount FROM transactions"
        f" WHERE user_id={user_id} AND transaction_type='PAYMENT'"
        " AND scheduled_expense_id IS NULL ORDER BY id DESC LIMIT 100) previous) sample"
    )
    monthly_budget_text, p95_text = shock_inputs.split("|")
    monthly_budget, p95 = int(monthly_budget_text), int(p95_text)
    shock_threshold = max(p95, (monthly_budget * 15 + 99) // 100)
    shock_amount = shock_threshold + 1
    check(
        0 < shock_threshold < 1_000_000_000_000_000,
        "실제 DB 표본 기반 shock 금액이 API 허용 범위",
        f"budget={monthly_budget} p95={p95} amount={shock_amount}",
    )
    failed_events_before = stack.psql(
        f"SELECT count(*) FROM replan_events WHERE goal_id={goal_id}"
        " AND trigger_type='LARGE_UNEXPECTED_TRANSACTION'"
        " AND proposed_plan_version_id IS NULL AND user_decision IS NULL"
    )
    active_plan_before = stack.psql(
        f"SELECT id FROM plan_versions WHERE goal_id={goal_id} AND status='ACTIVE'"
    )
    status, imported, _ = call(
        "POST", "/api/v1/transactions/import", token=token_a,
        body={"transactions": [{
            "transactionAt": iso_kst(now), "amount": shock_amount,
            "transactionType": "PAYMENT", "category": "REAL_RETRY_PREREQUISITE",
            "sourceId": "SCEN", "externalTransactionId": f"retry-shock-{secrets.token_hex(6)}",
        }]},
    )
    failed_events_after = stack.psql(
        f"SELECT count(*) FROM replan_events WHERE goal_id={goal_id}"
        " AND trigger_type='LARGE_UNEXPECTED_TRANSACTION'"
        " AND proposed_plan_version_id IS NULL AND user_decision IS NULL"
    )
    active_plan_after = stack.psql(
        f"SELECT id FROM plan_versions WHERE goal_id={goal_id} AND status='ACTIVE'"
    )
    check(
        status == 200 and imported.get("inserted") == 1
        and int(failed_events_after) == int(failed_events_before) + 1
        and active_plan_after == active_plan_before,
        "Analysis 장애 중 public 거래가 retry 가능한 shock event를 저장",
        f"status={status} events={failed_events_before}->{failed_events_after}"
        f" activePlan={active_plan_before}->{active_plan_after}",
    )
    stack.compose("start", "analysis-api")
    wait_service_healthy(stack, "analysis-api")

    # ── Redis 다운: 계획 생성은 성공, 설명은 즉시 FALLBACK으로 마감 ────
    # ExplanationQueuePublisher.publish()의 설계: Redis 발행이 실패하면 계획 저장은 그대로
    # 두고 같은 요청 스레드에서 별도 DB 트랜잭션으로 설명 상태만 FALLBACK으로 전환한다
    # (PENDING으로 방치해 사용자를 무기한 기다리게 하지 않는다). 실제로 돌려서 이 설계가
    # 맞다는 걸 확인했다 — 애초에 "PENDING으로 남는다"고 기대한 이전 버전 검증이 틀렸었다.
    stack.compose("stop", "redis")
    time.sleep(2)  # 컨테이너 종료 뒤 네트워크가 실제로 끊길 시간을 준다
    status, body, _ = call(
        "POST", f"/api/v1/goals/{goal_id}/plan-versions", token=token_a,
        body={"generationType": "USER_REQUESTED"},
    )
    check(status == 200, "Redis 다운에도 계획 생성 자체는 성공", f"status={status}")
    redis_down_plan_id = body["id"]
    check(
        body["explanation"]["status"] == "FALLBACK"
        and body["explanation"]["text"] == "계획 수치는 정상적으로 준비됐습니다. 현재는 설명 대신 계획 상세를 확인해 주세요.",
        "Redis 다운 시 설명은 즉시 FALLBACK으로 마감(무기한 PENDING 아님)",
        f"status={body['explanation']['status']}",
    )
    stack.compose("start", "redis")
    wait_service_healthy(stack, "redis")

    status, body, _ = call(
        "POST", f"/api/v1/goals/{goal_id}/plan-versions", token=token_a,
        body={"generationType": "USER_REQUESTED"},
    )
    check(status == 200, "Redis 복구 뒤 계획 생성 정상", f"status={status}")
    recovered_plan_id = body["id"]
    explanation_status = poll_explanation(stack, token_a, recovered_plan_id)
    check(
        explanation_status in ("READY", "FALLBACK"),
        "Redis 복구 뒤 새 설명은 정상 수렴", f"status={explanation_status}",
    )

    # ── 상한선(10^15) 금액: 월별 집계가 500 없이 처리 ─────────────────
    # 순수 int64 최댓값(Long.MAX_VALUE)은 그 사용자의 월별 집계 SQL VIEW SUM(...)::bigint를
    # 다른 거래와 합산할 때 BIGINT 범위를 넘겨 22003으로 깨졌다(실제 이 시나리오로 잡아냄).
    # 재발 방지로 TransactionDtos.MAX_AMOUNT(10^15) 입력 상한을 추가했으니, 상한선 값 자체는
    # 여전히 500 없이 적재·조회돼야 한다. 이 PAYMENT는 그 자체로 이번 달 평균 소비를 밀어올려
    # 이후 계획 계산을 real하게 infeasible하게 만들므로 위 down/up 검증 뒤로 미뤄 둔다.
    status, body, _ = call(
        "POST", "/api/v1/transactions/import", token=token_a,
        body={"transactions": [{
            "transactionAt": iso_kst(now), "amount": 1_000_000_000_000_000,
            "transactionType": "PAYMENT", "category": "쇼핑",
            "sourceId": "SCEN", "externalTransactionId": "pay-maxamount",
        }]},
    )
    check(status == 200 and body["inserted"] == 1, "상한선 금액은 500 없이 적재", f"status={status} {body}")
    status, body, _ = call("GET", "/api/v1/transactions", token=token_a, params={"limit": "5"})
    check(status == 200, "상한선 금액 적재 뒤에도 거래 조회 정상", f"status={status}")

    # ── 동시 예정지출 수정: 낙관적 잠금이 부분/중복 저장을 막음 ────────
    def patch(amount: int) -> tuple[int, object]:
        status, body, _ = call(
            "PATCH", f"/api/v1/scheduled-expenses/{expense_id}", token=token_a,
            body={"amount": amount},
        )
        return status, body

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(patch, 500000), pool.submit(patch, 600000)]
        outcomes = [future.result() for future in futures]
    statuses = sorted(status for status, _ in outcomes)
    check(
        statuses in ([200, 200], [200, 409]),
        "동시 예정지출 수정은 500 없이 낙관적 잠금으로 정리됨", f"statuses={statuses}",
    )
    final_amount = stack.psql(f"SELECT amount FROM scheduled_expenses WHERE id={expense_id}")
    check(
        final_amount in ("500000", "600000"),
        "동시 수정 뒤 최종 금액이 두 요청 중 하나로 일관됨", f"amount={final_amount}",
    )

    # ── secret 미노출 ─────────────────────────────────────────────
    logs = stack.compose("logs", "--no-color", capture=True).stdout
    leaked = any(
        secret in logs
        for secret in (
            stack.postgres_password, stack.redis_password, stack.internal_token,
            stack.jwt_secret, stack.e2e_token,
        )
    )
    check(not leaked, "컨테이너 로그에 secret 미노출", f"len(logs)={len(logs)}")


def wait_service_healthy(stack: Stack, service: str, timeout: float = 60.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = stack.compose(
            "ps", "--format", "json", service, capture=True
        )
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        if lines:
            state = json.loads(lines[0])
            if state.get("Health") == "healthy":
                return
        time.sleep(2)
    raise ScenarioFailure(f"{service}가 재기동 뒤 healthy 상태로 돌아오지 않았습니다")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--stack-smoke", action="store_true")
    parser.add_argument("--performance", action="store_true")
    parser.add_argument("--soak-minutes", type=int, default=0)
    args = parser.parse_args()
    if args.soak_minutes not in (0,) and args.soak_minutes < 30:
        parser.error("--soak-minutes must be 0 (off) or at least 30")
    if (args.performance or args.soak_minutes) and judge_period_active():
        print("REAL 시나리오 QA 거부: 심사 운영 기간에는 load/soak를 실행하지 않습니다", file=sys.stderr)
        return 2
    prerequisites = ("docker", "npm", "openssl")
    missing = [command for command in prerequisites if shutil.which(command) is None]
    if args.self_check:
        checks = {
            "missingCommands": missing,
            "composeFile": COMPOSE_FILE.exists(),
            "publicOperations": len(PUBLIC_OPERATIONS),
            "internalOperations": len(INTERNAL_OPERATIONS),
            "judgePeriodActive": judge_period_active(),
        }
        print(json.dumps(checks, ensure_ascii=False, sort_keys=True))
        return int(bool(missing) or not COMPOSE_FILE.exists()
                   or len(PUBLIC_OPERATIONS) != 34 or len(INTERNAL_OPERATIONS) != 5)
    if shutil.which("docker") is None:
        print("REAL 시나리오 QA 실패: docker가 필요합니다", file=sys.stderr)
        return 1
    if not COMPOSE_FILE.exists():
        print("REAL 시나리오 QA 실패: docker-compose.yml이 없습니다", file=sys.stderr)
        return 1

    stack = Stack()
    performance_report = None
    soak_report = None
    try:
        stack.setup_files()
        stack.audit_ports()
        stack.up()
        stack.fetch_ca()
        stack.wait_https()
        import_policy_fixtures(stack)
        if args.stack_smoke:
            print(json.dumps({
                "classification": "REAL",
                "project": stack.project,
                "components": ["Caddy", "Core", "PostgreSQL16.4", "Redis7.4", "Uvicorn"],
                "fixtures": ["PENDING_WRITE0", "UNAPPROVED_DATA_QA_FIXTURE", "SYNTHETIC_QA_ONLY"],
            }, ensure_ascii=False, sort_keys=True))
            return 0
        goal_id, expense_id, token_a, retry_username = run_scenario(stack)
        search_request, scenario_request = policy_requests(stack, token_a, goal_id)
        performance_report = run_performance(
            stack, token_a, search_request, scenario_request,
        ) if args.performance else None
        soak_report = run_soak(
            stack, token_a, search_request, scenario_request, args.soak_minutes,
        ) if args.soak_minutes else None
        if performance_report is not None:
            check(
                performance_report["passed"],
                "UNRESOLVED_UNAPPLIED_BASELINE",
                "절대 측정은 report에 기록했으나 c4 baseline ratio는 판정할 수 없음",
            )
        run_demo_scenario(stack, token_a)
        run_adversarial(stack, goal_id, expense_id, token_a)
        browser_evidence = run_browser_scenario(stack, retry_username)
        internal_evidence = parse_internal_access_log(
            stack.compose("logs", "--no-color", "analysis-api", capture=True).stdout,
            INTERNAL_OPERATIONS,
        )
        check(
            browser_evidence["publicSuccessCount"] == 33
            and browser_evidence["approvedBypassOperationCount"] == 1,
            "브라우저 Public 33 + APPROVED_BYPASS 1 gate",
            "strict markers verified",
        )
        check(
            internal_evidence["passed"],
            "Internal operation 5/5 access-log gate",
            f"success={internal_evidence['successCount']}/5",
        )
    except ScenarioFailure as failure:
        print(json.dumps({
            "classification": "FAILED",
            "reason": str(failure).split(" —", 1)[0],
            "rerun": "python3 scripts/real_scenario_qa.py",
            "performance": performance_report,
            "soak": soak_report,
        }, ensure_ascii=False, sort_keys=True), file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as error:
        print(json.dumps({
            "classification": "FAILED", "reason": "EXTERNAL_COMMAND_FAILED",
            "returncode": error.returncode,
            "rerun": "python3 scripts/real_scenario_qa.py",
        }, ensure_ascii=False, sort_keys=True), file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 — cleanup 뒤 controlled failure로 정규화
        print(json.dumps({
            "classification": "FAILED", "reason": type(error).__name__,
            "rerun": "python3 scripts/real_scenario_qa.py",
        }, ensure_ascii=False, sort_keys=True), file=sys.stderr)
        return 1
    else:
        successful = {
            item["operationId"] for item in stack.evidence
            if item["operationId"] and 200 <= item["status"] < 300
        }
        bypass_count = sum(
            item["observedVia"] == "APPROVED_BYPASS" for item in stack.evidence
        )
        report = {
            "classification": "REAL",
            "project": stack.project,
            "components": ["Caddy", "Core", "PostgreSQL16.4", "Redis7.4", "Uvicorn", "Web/Playwright"],
            "fixtures": ["UNAPPROVED_DATA_QA_FIXTURE", "SYNTHETIC_QA_ONLY"],
            "directEvidence": {
                "classification": "PYTHON_DIRECT_REAL",
                "publicSuccessCount": len(successful.intersection(
                operation.operation_id for operation in PUBLIC_OPERATIONS
                )),
                "authSetupCallCount": bypass_count,
                "evidence": stack.evidence,
            },
            "browserEvidence": browser_evidence,
            "internalEvidence": internal_evidence,
            "publicProductOperationsExpected": 33,
            "approvedBypassOperationsExpected": 1,
            "internalExpected": 5,
            "performance": performance_report,
            "soak": soak_report,
        }
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    finally:
        stack.cleanup()

    print(f"\nREAL 시나리오 QA 통과: {len(PASSED)}개 단계 ({stack.project})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
