package com.dacon.core.user.dto;

import com.dacon.core.user.entity.ReplanOutcome;
import java.time.Instant;

/**
 * 저장된 금융정보와 그 변경으로 발생할 수 있는 재계획 결과다. 모든 금액은 원 단위다.
 *
 * @param monthlyIncome 저장된 월소득
 * @param monthlyFixedCost 저장된 월고정비
 * @param updatedAt 마지막 저장 시각
 * @param triggeredReplanEventId 생성된 재계획 이벤트 ID, 없으면 {@code null}
 * @param replanOutcome 재계획 미발생·제안·불가능 결과
 * @param planVersionId 생성된 계획 버전 ID, 없으면 {@code null}
 * @param shortfallAmount 달성 불가능한 경우 부족액, 해당하지 않으면 {@code null}
 */
public record FinancialProfileResponse(
    long monthlyIncome,
    long monthlyFixedCost,
    Instant updatedAt,
    Integer triggeredReplanEventId,
    ReplanOutcome replanOutcome,
    Integer planVersionId,
    Long shortfallAmount) {}
