from datetime import UTC, datetime

from app.models import ExplanationRequest, ExplanationResponse

TEMPLATE_MODEL = "deterministic-template-v1"


def _won(value: int) -> str:
    """확정된 원 단위 정수를 한국어 금액 표기로 변환한다."""

    return f"{value:,}원"


def _template_text(request: ExplanationRequest) -> str:
    """확정된 계산 결과만 사용해 결정론적인 계획 설명을 만든다."""

    plan = request.plan
    sentences = [
        f"현재 계획의 권장 월 변동지출은 {_won(plan.recommended_monthly_spending)}입니다.",
        (
            f"최근 월평균 변동지출은 {_won(plan.current_avg_variable_spending)}이며, "
            f"남은 기간은 {plan.remaining_months}개월입니다."
        ),
    ]
    if plan.target_amount is not None:
        sentences.append(f"목표 금액은 {_won(plan.target_amount)}입니다.")
    if plan.current_saved_amount is not None:
        sentences.append(f"현재 저축액은 {_won(plan.current_saved_amount)}입니다.")
    if plan.aggressive_warning:
        sentences.append(
            "최근 소비 수준과 비교해 실천 부담이 큰 계획이므로 지출 내역을 자주 확인해 주세요."
        )
    else:
        sentences.append("현재 계획에 맞춰 지출 내역을 꾸준히 확인해 주세요.")
    return " ".join(sentences)


def generate_explanation(request: ExplanationRequest) -> ExplanationResponse:
    """외부 호출 없이 확정된 계획을 고정 템플릿으로 설명한다."""

    return ExplanationResponse(
        status="READY",
        text=_template_text(request),
        model=TEMPLATE_MODEL,
        retry_count=0,
        failed_numbers=[],
        generated_at=datetime.now(UTC),
    )
