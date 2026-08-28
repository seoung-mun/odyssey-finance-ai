package com.dacon.core.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 월 금융정보 입력이다. 모든 금액은 원 단위다.
 *
 * @param monthlyIncome 0 이상의 월소득
 * @param monthlyFixedCost 0 이상의 월고정비
 */
public record FinancialProfileInput(
    @NotNull @PositiveOrZero Long monthlyIncome, @NotNull @PositiveOrZero Long monthlyFixedCost) {}
