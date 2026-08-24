import os
import secrets
from datetime import UTC, datetime
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

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


def is_valid_internal_token(provided: str | None, expected: str | None) -> bool:
    return bool(provided and expected) and secrets.compare_digest(provided, expected)


def require_internal_token(
    token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
) -> None:
    if not is_valid_internal_token(token, os.getenv("INTERNAL_API_TOKEN")):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="유효하지 않은 내부 API 토큰",
        )


app = FastAPI(title="analysis-api", dependencies=[Depends(require_internal_token)])


@app.get("/internal/health")
def health():
    return {
        "status": "ok",
        "engineVersion": ENGINE_VERSION,
        "llmModel": "",
        "llmReady": False,
    }


@app.exception_handler(RequestValidationError)
def handle_validation_error(_request: Request, exc: RequestValidationError):
    errors = jsonable_encoder(exc.errors())
    locations = {str(part) for error in errors for part in error["loc"]}
    if "historicalMonthlyVariableSpending" in locations:
        code = "INSUFFICIENT_HISTORY"
    elif {"horizonMonths", "remainingScheduledExpenses"} & locations:
        code = "INVALID_HORIZON"
    else:
        code = "INVALID_INPUT"
    body = ComputeError(code=code, message="요청 값을 확인해 주세요", detail={"errors": errors})
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True, mode="json"))


@app.exception_handler(ComputeInputError)
def handle_compute_error(_request: Request, exc: ComputeInputError):
    body = ComputeError(code=exc.code, message=exc.message, detail=exc.detail)
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True, mode="json"))


@app.post("/internal/simulate", response_model=SimulateResponse)
def simulate(request: SimulateRequest):
    return compute_presets(
        request.model_dump(),
        input_snapshot=request.model_dump(by_alias=True, mode="json"),
    )


@app.post("/internal/custom-option", response_model=CustomOptionResponse)
def custom_option(request: CustomOptionRequest):
    return compute_custom(
        request.model_dump(),
        input_snapshot=request.model_dump(by_alias=True, mode="json"),
    )


@app.post("/internal/explanations", response_model=ExplanationResponse)
def explanations(_request: ExplanationRequest):
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
