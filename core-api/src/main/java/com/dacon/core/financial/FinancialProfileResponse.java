package com.dacon.core.financial;

import java.time.Instant;

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
