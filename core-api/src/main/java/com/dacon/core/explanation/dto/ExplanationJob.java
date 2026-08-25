package com.dacon.core.explanation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FastAPI 설명 요청에 필요한 불변 계획 snapshot이다. */
public record ExplanationJob(
    long planVersionId,
    String status,
    String inputHash,
    String promptVersion,
    Long recommendedMonthlySpending,
    long currentAvgVariableSpending,
    long targetAmount,
    long currentSavedAmount,
    LocalDate asOfDate,
    LocalDate targetDate,
    BigDecimal simulationCoverage,
    Boolean aggressiveWarning) {}
