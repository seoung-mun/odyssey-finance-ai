package com.dacon.core.transaction.dto;

import com.dacon.core.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 거래 적재·페이지 조회·소비 집계 HTTP 계약의 불변 타입을 모은다. */
public final class TransactionDtos {
  private TransactionDtos() {}

  /**
   * 외부 거래 한 건의 입력이다.
   *
   * @param transactionAt offset이 포함된 거래 시각
   * @param amount 항상 양수인 금액(원)
   * @param transactionType 금액 방향을 정하는 PAYMENT 또는 REFUND
   * @param category 30자 이하 소비 카테고리
   * @param merchantName 선택적 가맹점 이름
   * @param mcc 선택적 업종 코드
   * @param scheduledExpenseId 연결할 사용자 소유 예정지출 ID
   * @param externalTransactionId 사용자 범위에서 중복 적재를 막는 외부 ID
   */
  public record TransactionInput(
      @NotNull OffsetDateTime transactionAt,
      @Positive long amount,
      @NotNull TransactionType transactionType,
      @NotBlank @Size(max = 30) String category,
      @Size(max = 200) String merchantName,
      Short mcc,
      Integer scheduledExpenseId,
      @NotBlank @Size(max = 50) String sourceId,
      @NotBlank @Size(max = 100) String externalTransactionId,
      @Size(max = 100) List<@Valid RefundAllocationInput> refundAllocations) {}

  /** REFUND가 원 PAYMENT에 배분할 금액과 정확한 외부 식별자다. */
  public record RefundAllocationInput(
      @NotBlank @Size(max = 50) String paymentSourceId,
      @NotBlank @Size(max = 100) String paymentExternalTransactionId,
      @Positive long amount) {}

  /**
   * 최대 천 건의 거래 일괄 적재 요청이다.
   *
   * @param transactions 각 항목까지 검증할 거래 목록
   */
  public record ImportRequest(
      @NotNull @Size(max = 1000) List<@Valid TransactionInput> transactions) {}

  /**
   * 거래 일괄 적재 결과다.
   *
   * @param inserted 새로 삽입된 건수
   * @param skipped 같은 사용자·외부 거래 ID로 이미 존재해 건너뛴 건수
   */
  public record ImportResult(
      int inserted, int skipped, int allocationsInserted, int allocationsPending) {}

  /**
   * 거래 조회 항목이다.
   *
   * @param id 내부 거래 ID
   * @param transactionAt offset 포함 거래 시각
   * @param amount 양의 금액(원)
   * @param transactionType PAYMENT 또는 REFUND
   * @param category 소비 카테고리
   * @param merchantName 선택적 가맹점 이름
   * @param mcc 선택적 업종 코드
   * @param scheduledExpenseId 연결 예정지출 ID
   * @param externalTransactionId 외부 멱등 ID
   */
  public record TransactionResponse(
      long id,
      OffsetDateTime transactionAt,
      long amount,
      String transactionType,
      String category,
      String merchantName,
      Short mcc,
      Integer scheduledExpenseId,
      String sourceId,
      String externalTransactionId,
      String refundStatus,
      long resolvedRefundAmount,
      long pendingRefundAmount,
      long unmatchedRefundAmount,
      List<RefundAllocation> refundAllocations) {}

  /** 조회 화면에 노출하는 환불 배분 상태다. */
  public record RefundAllocation(
      long id,
      Long paymentTransactionId,
      String paymentSourceId,
      String paymentExternalTransactionId,
      long amount,
      String status) {}

  /**
   * 커서 기반 거래 조회 결과다.
   *
   * @param items ID 내림차순 거래 목록
   * @param nextCursor 다음 페이지의 상한 ID, 마지막 페이지면 {@code null}
   */
  public record TransactionPage(List<TransactionResponse> items, String nextCursor) {}

  /**
   * KST 월별 소비 집계다.
   *
   * @param yearMonth 해당 월 1일
   * @param totalVariableSpending 예정지출 매칭분을 포함한 순 소비(원)
   * @param bootstrapEligibleSpending 유효 예정지출 매칭분을 제외한 순 소비(원)
   */
  public record MonthlySpending(
      LocalDate yearMonth,
      long totalVariableSpending,
      long grossPaymentSpending,
      long linkedRefundAmount,
      long unmatchedRefundInflow,
      long adjustedConsumption,
      long netCashFlow,
      long bootstrapEligibleSpending) {}

  /**
   * 카테고리별 월평균 항목이다.
   *
   * @param category 소비 카테고리
   * @param monthlyAverage 요청 개월 수로 나누고 원 단위로 반올림한 순 소비
   */
  public record CategorySpending(String category, long monthlyAverage) {}

  /**
   * 카테고리 소비 집계다.
   *
   * @param months 평균 계산에 사용한 KST 달력 월 수
   * @param currentAvgVariableSpending 전체 월평균 순 소비(원)
   * @param categories 카테고리 이름 순 월평균 목록
   */
  public record CategorySummary(
      int months, long currentAvgVariableSpending, List<CategorySpending> categories) {}
}
