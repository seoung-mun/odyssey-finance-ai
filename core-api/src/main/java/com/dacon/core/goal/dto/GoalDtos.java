package com.dacon.core.goal.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/** 목표와 예정지출 HTTP 계약에서만 사용하는 불변 요청·응답 타입을 모은다. */
public final class GoalDtos {
  private GoalDtos() {}

  /**
   * 목표 생성 요청이다.
   *
   * @param name 비어 있지 않은 100자 이하 목표 이름
   * @param targetAmount 양의 목표 금액(원)
   * @param currentSavedAmount 0 이상의 현재 저축액(원)
   * @param targetDate 현재보다 미래인 목표일
   */
  public record GoalRequest(
      @NotBlank @Size(max = 100) String name,
      @Positive long targetAmount,
      @PositiveOrZero long currentSavedAmount,
      @NotNull @Future LocalDate targetDate) {}

  public record GoalPatch(
      @Size(min = 1, max = 100) String name,
      @Positive Long targetAmount,
      @PositiveOrZero Long currentSavedAmount,
      LocalDate targetDate,
      @Pattern(regexp = "ACTIVE|ACHIEVED|CANCELLED") String status) {}

  /**
   * 목표 조회·생성 응답이다.
   *
   * @param id 목표 ID
   * @param name 목표 이름
   * @param targetAmount 목표 금액(원)
   * @param currentSavedAmount 현재 저축액(원)
   * @param targetDate 목표일
   * @param status 현재 목표 상태
   * @param remainingMonths KST 현재 월과 목표 월을 포함한 남은 달 수
   * @param spendingReplanSuppressedUntil 소비 이탈 재계획 억제 종료일
   * @param createdAt 생성 시각
   */
  public record GoalResponse(
      int id,
      String name,
      long targetAmount,
      long currentSavedAmount,
      LocalDate targetDate,
      String status,
      int remainingMonths,
      LocalDate spendingReplanSuppressedUntil,
      Instant createdAt,
      Integer triggeredReplanEventId) {}

  /**
   * 예정지출과 실제 연결 거래 집계 응답이다.
   *
   * @param id 예정지출 ID
   * @param name 예정지출 이름
   * @param amount 확정 예정 금액(원)
   * @param scheduledDate 예정일
   * @param status 현재 상태
   * @param matchedTransactionCount 연결 거래 건수
   * @param matchedAmount 연결 거래의 PAYMENT-REFUND 순액(원)
   */
  public record ScheduledExpenseResponse(
      int id,
      String name,
      long amount,
      LocalDate scheduledDate,
      String status,
      long matchedTransactionCount,
      long matchedAmount,
      Integer triggeredReplanEventId) {}

  public record ScheduledExpenseInput(
      @NotBlank @Size(max = 100) String name,
      @Positive long amount,
      @NotNull LocalDate scheduledDate) {}

  public record ScheduledExpensePatch(
      @Size(min = 1, max = 100) String name,
      @Positive Long amount,
      LocalDate scheduledDate,
      @Pattern(regexp = "PLANNED|COMPLETED|CANCELLED") String status) {}
}
