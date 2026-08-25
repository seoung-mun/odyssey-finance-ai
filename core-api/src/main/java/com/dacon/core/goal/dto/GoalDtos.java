package com.dacon.core.goal.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/** 목표 API의 요청과 응답 DTO를 모은다. */
public final class GoalDtos {
  private GoalDtos() {}

  /** 목표 생성 요청이다. */
  public record GoalRequest(
      @NotBlank @Size(max = 100) String name,
      @Positive long targetAmount,
      @PositiveOrZero long currentSavedAmount,
      @Future LocalDate targetDate) {}

  /** 목표 조회·생성 응답이다. */
  public record GoalResponse(
      int id,
      String name,
      long targetAmount,
      long currentSavedAmount,
      LocalDate targetDate,
      String status,
      int remainingMonths,
      LocalDate spendingReplanSuppressedUntil,
      Instant createdAt) {}

  /** 예정지출 조회 응답이다. */
  public record ScheduledExpenseResponse(
      int id,
      String name,
      long amount,
      LocalDate scheduledDate,
      String status,
      long matchedTransactionCount,
      long matchedAmount) {}
}
