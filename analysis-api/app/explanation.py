import http.client
import json
import os
import re
import socket
import threading
from datetime import UTC, datetime
from decimal import Decimal, DecimalException
from time import monotonic
from urllib.parse import urlsplit

from app.models import ExplanationRequest, ExplanationResponse

MODEL = "qwen3.5:2b-q4_K_M"
TOTAL_TIMEOUT_SECONDS = 15.0
MAX_RESPONSE_BYTES = 256 * 1024
FALLBACK_TEXT = (
    "계산된 계획을 확인해 주세요.\n"
    "현재 소비 흐름을 반영했습니다.\n"
    "상황이 바뀌면 다시 계산할 수 있습니다."
)
_ASCII_NUMBER = re.compile(
    r"[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?%?"
)
_KOREAN_NUMBER_WITH_UNIT = re.compile(
    r"(?:[영공일이삼사오육칠팔구십백천만억조]+|"
    r"(?:(?:하나|둘|셋|넷|다섯|여섯|일곱|여덟|아홉|열|스물|서른|마흔|쉰|예순|"
    r"일흔|여든|아흔|한|두|세|네))+)[ ]*(?:원|개월|달|년|주|일|퍼센트|프로|배|할|푼)"
)
_inference_lock = threading.Lock()


def _normalized_number(value: str | int) -> str:
    """숫자의 쉼표·소수·지수 표면형을 비교 가능한 문자열로 바꾼다."""

    number = Decimal(str(value).replace(",", "").removesuffix("%"))
    digits = number.as_tuple().digits
    if len(digits) > 30 or abs(number.as_tuple().exponent) > 30:
        raise ValueError("숫자 표현이 너무 큽니다")
    return format(number.normalize(), "f")


def _failed_numbers(text: str, allowed_numbers: list[int]) -> list[str]:
    """설명에 등장한 숫자 중 허용 목록에 없는 값을 순서대로 반환한다."""

    allowed = {_normalized_number(number) for number in allowed_numbers}
    violations: list[tuple[int, str]] = []
    for match in _ASCII_NUMBER.finditer(text):
        try:
            normalized = _normalized_number(match.group())
        except (DecimalException, ValueError):
            normalized = "<UNSAFE_NUMBER>"
        if normalized not in allowed:
            violations.append((match.start(), normalized))
    violations.extend(
        (index, "<NON_ASCII_NUMERIC>")
        for index, char in enumerate(text)
        if char.isnumeric() and not "0" <= char <= "9"
    )
    violations.extend(
        (match.start(), "<KOREAN_NUMERAL>")
        for match in _KOREAN_NUMBER_WITH_UNIT.finditer(text)
    )
    failed = []
    for _position, value in sorted(violations):
        if value not in failed:
            failed.append(value)
    return failed


def _ollama_target(deadline: float):
    """검증된 Ollama 연결, 고정 API 경로와 남은 timeout을 반환한다."""

    parsed = urlsplit(os.getenv("OLLAMA_URL", "http://localhost:11434"))
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("OLLAMA_URL 형식이 올바르지 않습니다")
    remaining = deadline - monotonic()
    if remaining <= 0:
        raise TimeoutError("전체 설명 생성 시간이 초과됐습니다")
    connection_type = (
        http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    )
    return connection_type(parsed.hostname, parsed.port, timeout=remaining), "/api/generate"


def _interrupt(
    connection: http.client.HTTPConnection, active_socket: list[socket.socket | None]
) -> None:
    """deadline에 활성 socket을 끊어 header/body 대기를 중단한다."""

    current_socket = active_socket[0] or connection.sock
    if current_socket:
        try:
            current_socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        current_socket.close()


def _ollama_request(payload: dict, deadline: float):
    """Ollama 요청 전체를 단일 deadline과 제한된 응답 크기 안에서 수행한다."""

    connection, path = _ollama_target(deadline)
    active_socket: list[socket.socket | None] = [None]
    timer = threading.Timer(
        max(0.0, deadline - monotonic()), _interrupt, args=(connection, active_socket)
    )
    timer.daemon = True
    timer.start()
    try:
        connection.request(
            "POST",
            path,
            body=json.dumps(payload, ensure_ascii=False).encode(),
            headers={"Content-Type": "application/json", "Connection": "close"},
        )
        active_socket[0] = connection.sock
        response = connection.getresponse()
        try:
            active_socket[0] = response.fp.raw._sock
        except AttributeError:
            pass
        if not 200 <= response.status < 300:
            raise ValueError("Ollama HTTP 오류")
        body = bytearray()
        while True:
            if monotonic() >= deadline:
                raise TimeoutError("전체 설명 생성 시간이 초과됐습니다")
            chunk = response.read(min(8192, MAX_RESPONSE_BYTES + 1 - len(body)))
            if not chunk:
                break
            body.extend(chunk)
            if len(body) > MAX_RESPONSE_BYTES:
                raise ValueError("Ollama 응답이 너무 큽니다")
        parsed = json.loads(body)
        if monotonic() >= deadline:
            raise TimeoutError("전체 설명 생성 시간이 초과됐습니다")
        return parsed
    finally:
        timer.cancel()
        connection.close()
        timer.join()


def _prompt(request: ExplanationRequest, failed: list[str]) -> str:
    """확정 계획과 직전 실패 숫자만 포함한 생성 프롬프트를 만든다."""

    source = {
        "plan": request.plan.model_dump(by_alias=True, mode="json"),
        "previousPlan": (
            request.previous_plan.model_dump(by_alias=True, mode="json")
            if request.previous_plan
            else None
        ),
    }
    correction = (
        " 직전 응답의 허용되지 않은 숫자는 " + ", ".join(failed) + "입니다. 다시 쓰세요."
        if failed
        else ""
    )
    return (
        "아래 확정 JSON만 설명하는 짧은 한국어 문장을 세네 줄로 쓰세요. "
        "계산하거나 단위를 바꾸거나 새 숫자를 만들지 말고 JSON의 숫자 표면형을 그대로 쓰세요. "
        "숫자를 생략해도 됩니다." + correction + "\n" + json.dumps(source, ensure_ascii=False)
    )


def _fallback(retry_count: int, failed_numbers: list[str]) -> ExplanationResponse:
    """숫자가 전혀 없는 안전한 대체 응답을 만든다."""

    return ExplanationResponse(
        status="FALLBACK",
        text=FALLBACK_TEXT,
        model=None,
        retry_count=retry_count,
        failed_numbers=failed_numbers,
        generated_at=datetime.now(UTC),
    )


def generate_explanation(request: ExplanationRequest) -> ExplanationResponse:
    """Ollama 설명을 직렬 생성하고 숫자 검증 실패 시 제한 안에서 교정한다."""

    started = monotonic()
    deadline = started + TOTAL_TIMEOUT_SECONDS
    acquired = _inference_lock.acquire(
        timeout=max(0.0, deadline - monotonic())
    )
    if not acquired:
        return _fallback(0, [])

    corrections = 0
    all_failed: list[str] = []
    try:
        for attempt in range(request.max_retry + 1):
            remaining = deadline - monotonic()
            if remaining <= 0:
                return _fallback(corrections, all_failed)
            if attempt:
                corrections = attempt
            try:
                body = _ollama_request(
                    payload={
                        "model": MODEL,
                        "prompt": _prompt(request, all_failed),
                        "stream": False,
                        "think": False,
                        "options": {
                            "num_ctx": 2048,
                            "num_predict": 150,
                            "temperature": 0.3,
                        },
                    },
                    deadline=deadline,
                )
                text = body.get("response") if isinstance(body, dict) else None
                if not isinstance(text, str) or not text.strip():
                    return _fallback(corrections, all_failed)
            except (http.client.HTTPException, OSError, TimeoutError, TypeError, ValueError):
                return _fallback(corrections, all_failed)

            failed = _failed_numbers(text, request.allowed_numbers)
            if monotonic() >= deadline:
                return _fallback(corrections, all_failed)
            if not failed:
                return ExplanationResponse(
                    status="READY",
                    text=text.strip(),
                    model=MODEL,
                    retry_count=corrections,
                    failed_numbers=[],
                    generated_at=datetime.now(UTC),
                )
            for number in failed:
                if number not in all_failed:
                    all_failed.append(number)
        return _fallback(corrections, all_failed)
    finally:
        _inference_lock.release()
