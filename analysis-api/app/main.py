import os
import secrets
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, status


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
    return {"status": "ok"}


if __name__ == "__main__":
    assert is_valid_internal_token("secret", "secret")
    assert not is_valid_internal_token("wrong", "secret")
    assert not is_valid_internal_token(None, "secret")
    assert not is_valid_internal_token("", "")
    print("main.py self-check OK")
