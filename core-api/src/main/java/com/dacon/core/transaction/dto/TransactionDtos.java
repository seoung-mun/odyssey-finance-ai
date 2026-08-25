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

/** 거래 import API의 요청과 응답 DTO를 모은다. */
public final class TransactionDtos {
  private TransactionDtos() {}

  /** 외부 거래 한 건의 입력이다. */
  public record TransactionInput(
      @NotNull OffsetDateTime transactionAt,
      @Positive long amount,
      @NotNull TransactionType transactionType,
      @NotBlank @Size(max = 30) String category,
      @Size(max = 200) String merchantName,
      Short mcc,
      Integer scheduledExpenseId,
      @NotBlank @Size(max = 100) String externalTransactionId) {}

  /** 최대 천 건의 거래 import 요청이다. */
  public record ImportRequest(
      @NotNull @Size(max = 1000) List<@Valid TransactionInput> transactions) {}

  /** 거래 적재 및 중복 건수다. */
  public record ImportResult(int inserted, int skipped) {}

  /** 거래 조회 항목이다. */
  public record TransactionResponse(
      long id,
      OffsetDateTime transactionAt,
      long amount,
      String transactionType,
      String category,
      String merchantName,
      Short mcc,
      Integer scheduledExpenseId,
      String externalTransactionId) {}

  /** 커서 기반 거래 조회 결과다. */
  public record TransactionPage(List<TransactionResponse> items, String nextCursor) {}

  /** KST 월별 소비 집계다. */
  public record MonthlySpending(
      LocalDate yearMonth, long totalVariableSpending, long bootstrapEligibleSpending) {}

  /** 카테고리별 월평균 항목이다. */
  public record CategorySpending(String category, long monthlyAverage) {}

  /** 카테고리 소비 집계다. */
  public record CategorySummary(
      int months, long currentAvgVariableSpending, List<CategorySpending> categories) {}
}
