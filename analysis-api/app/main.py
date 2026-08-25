import logging
import os
import re
import secrets
import time
import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader

from app.models import (
    ComputeError,
    CustomOptionRequest,
    CustomOptionResponse,
    ExplanationRequest,
    ExplanationResponse,
    SimulateRequest,
    SimulateResponse,
)
from engine.planning import ENGINE_VERSION, ComputeInputError, compute_custom, compute_presets

log_level = getattr(logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO)
logging.basicConfig(
    level=log_level,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("analysis_api")
logger.setLevel(log_level)

internal_api_key = APIKeyHeader(
    name="X-Internal-Token", scheme_name="internalApiKey", auto_error=False
)


def is_valid_internal_token(provided: str | None, expected: str | None) -> bool:
    """제공된 내부 토큰과 환경변수 토큰이 모두 있고 같은지 비교한다."""

    return bool(provided and expected) and secrets.compare_digest(provided, expected)


def request_id(request: Request) -> str:
    """안전한 요청 ID 헤더를 재사용하고 없거나 부적절하면 새 UUID를 반환한다."""

    provided = request.headers.get("X-Request-ID", "")
    return provided if re.fullmatch(r"[A-Za-z0-9._-]{1,64}", provided) else uuid.uuid4().hex


def validation_code(errors: list[dict]) -> str:
    """검증 오류 목록 전체가 같은 전용 범주일 때만 해당 ComputeError 코드를 반환한다."""

    if errors and all(
        error["type"] == "too_short" and error["loc"][-1] == "historicalMonthlyVariableSpending"
        for error in errors
    ):
        return "INSUFFICIENT_HISTORY"
    if errors and all(
        error["type"] == "invalid_horizon" or error["loc"][-1] == "horizonMonths"
        for error in errors
    ):
        return "INVALID_HORIZON"
    return "INVALID_INPUT"


def require_internal_token(
    request: Request,
    token: Annotated[str | None, Depends(internal_api_key)],
) -> None:
    """요청의 내부 API 토큰을 검증하고 실패하면 401을 발생시킨다."""

    if not is_valid_internal_token(token, os.getenv("INTERNAL_API_TOKEN")):
        logger.warning(
            "request_rejected request_id=%s method=%s path=%s reason=invalid_token",
            request.state.request_id,
            request.method,
            request.url.path,
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="유효하지 않은 내부 API 토큰",
        )


app = FastAPI(
    title="analysis-api",
    version="1.2.0",
    dependencies=[Depends(require_internal_token)],
)


@app.middleware("http")
async def log_request(request: Request, call_next):
    """요청 결과를 기록하고 예상하지 못한 예외는 요청 ID가 붙은 500으로 반환한다."""

    request.state.request_id = request_id(request)
    started = time.perf_counter()
    logger.debug(
        "request_started request_id=%s method=%s path=%s",
        request.state.request_id,
        request.method,
        request.url.path,
    )
    try:
        response = await call_next(request)
    except Exception:
        logger.exception(
            "request_failed request_id=%s method=%s path=%s duration_ms=%.1f",
            request.state.request_id,
            request.method,
            request.url.path,
            (time.perf_counter() - started) * 1000,
        )
        body = ComputeError(message="서버 오류가 발생했습니다")
        response = JSONResponse(
            status_code=500,
            content=body.model_dump(by_alias=True, mode="json"),
        )
    response.headers["X-Request-ID"] = request.state.request_id
    logger.info(
        "request_completed request_id=%s method=%s path=%s status=%s duration_ms=%.1f",
        request.state.request_id,
        request.method,
        request.url.path,
        response.status_code,
        (time.perf_counter() - started) * 1000,
    )
    return response


@app.get("/internal/health", operation_id="getInternalHealth")
def health():
    """엔진 버전과 설명 API fallback 준비 상태를 반환한다."""

    return {
        "status": "ok",
        "engineVersion": ENGINE_VERSION,
        "llmModel": "",
        "llmReady": False,
    }


@app.exception_handler(RequestValidationError)
def handle_validation_error(request: Request, exc: RequestValidationError):
    """Pydantic 요청 검증 오류를 계약에 맞는 422 ComputeError로 변환한다."""

    errors = jsonable_encoder(exc.errors())
    code = validation_code(errors)
    logger.warning(
        "request_validation_failed request_id=%s method=%s path=%s code=%s error_count=%s",
        request.state.request_id,
        request.method,
        request.url.path,
        code,
        len(errors),
    )
    body = ComputeError(code=code, message="요청 값을 확인해 주세요", detail={"errors": errors})
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True, mode="json"))


@app.exception_handler(ComputeInputError)
def handle_compute_error(request: Request, exc: ComputeInputError):
    """예상 가능한 계산 입력 오류를 422 ComputeError로 변환한다."""

    logger.warning(
        "compute_rejected request_id=%s method=%s path=%s code=%s",
        request.state.request_id,
        request.method,
        request.url.path,
        exc.code,
    )
    body = ComputeError(code=exc.code, message=exc.message, detail=exc.detail)
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True, mode="json"))


@app.post(
    "/internal/simulate",
    operation_id="simulatePlan",
    response_model=SimulateResponse,
    responses={422: {"model": ComputeError}, 500: {"model": ComputeError}},
)
def simulate(request: SimulateRequest):
    """검증된 입력으로 모든 PRESET 옵션과 누적저축 밴드를 계산한다."""

    return compute_presets(
        request.model_dump(),
        input_snapshot=request.model_dump(by_alias=True, mode="json"),
    )


@app.post(
    "/internal/custom-option",
    operation_id="computeCustomOption",
    response_model=CustomOptionResponse,
    responses={422: {"model": ComputeError}, 500: {"model": ComputeError}},
)
def custom_option(request: CustomOptionRequest):
    """검증된 사용자 월지출 기준으로 CUSTOM 옵션과 누적저축 밴드를 계산한다."""

    return compute_custom(
        request.model_dump(),
        input_snapshot=request.model_dump(by_alias=True, mode="json"),
    )


@app.post(
    "/internal/explanations",
    operation_id="generateExplanation",
    response_model=ExplanationResponse,
)
def explanations(_request: ExplanationRequest):
    """LLM 연동 전까지 숫자 없는 고정 fallback 설명을 반환한다."""

    return ExplanationResponse(
        status="FALLBACK",
        text=(
            "계산된 계획을 확인해 주세요.\n"
            "현재 소비 흐름을 반영했습니다.\n"
            "상황이 바뀌면 다시 계산할 수 있습니다."
        ),
        model=None,
        retry_count=0,
        failed_numbers=[],
        generated_at=datetime.now(UTC),
    )


if __name__ == "__main__":
    assert is_valid_internal_token("secret", "secret")
    assert not is_valid_internal_token("wrong", "secret")
    assert not is_valid_internal_token(None, "secret")
    assert not is_valid_internal_token("", "")
    print("main.py self-check OK")
