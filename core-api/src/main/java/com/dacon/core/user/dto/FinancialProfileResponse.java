package com.dacon.core.user.dto;

import com.dacon.core.user.entity.ReplanOutcome;
import com.dacon.core.user.entity.SpendingFloorMode;
import java.time.Instant;

/** 저장된 금융정보와 선택적 재계획 결과다. */
public record FinancialProfileResponse(
    long monthlyIncome,
    long monthlyFixedCost,
    SpendingFloorMode spendingFloorMode,
    Long customMonthlyVariableFloor,
    Instant updatedAt,
    Integer triggeredReplanEventId,
    ReplanOutcome replanOutcome,
    Integer planVersionId,
    Long shortfallAmount) {}
