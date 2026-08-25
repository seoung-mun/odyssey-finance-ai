package com.dacon.core.transaction;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 거래 영속화와 PostgreSQL 집계를 Spring Data 경계에 숨긴다. */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
  boolean existsByUserId(int userId);

  @Query(
      "select tx from Transaction tx where tx.user.id=:userId and (:from is null or tx.transactionAt>=:from) and (:to is null or tx.transactionAt<:to) and (:category is null or tx.category=:category) and tx.id<:cursor order by tx.id desc")
  List<Transaction> findPage(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to,
      @Param("category") String category,
      @Param("cursor") long cursor,
      Pageable pageable);

  @Query(
      value =
          "SELECT period_month AS \"yearMonth\", total_variable_spending AS \"totalVariableSpending\", bootstrap_eligible_spending AS \"bootstrapEligibleSpending\" FROM monthly_spending_window(:userId,:from,:to)",
      nativeQuery = true)
  List<MonthlySummaryView> monthlySummary(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  @Query(
      "select tx.category as category, coalesce(sum(case when tx.transactionType='PAYMENT' then tx.amount else -tx.amount end),0) as total from Transaction tx where tx.user.id=:userId and tx.transactionAt>=:from and tx.transactionAt<:to group by tx.category order by tx.category")
  List<CategoryTotalView> categoryTotals(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /** 부분 unique index 기반 멱등 insert 결과를 반환한다. */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO transactions(user_id,transaction_at,amount,transaction_type,category,
            merchant_name,mcc,scheduled_expense_id,external_transaction_id)
          VALUES (:userId,:transactionAt,:amount,:transactionType,:category,:merchantName,:mcc,
            :scheduledExpenseId,:externalTransactionId)
          ON CONFLICT (user_id,external_transaction_id)
            WHERE external_transaction_id IS NOT NULL DO NOTHING
          """,
      nativeQuery = true)
  int insertIgnoringDuplicate(
      @Param("userId") int userId,
      @Param("transactionAt") OffsetDateTime transactionAt,
      @Param("amount") long amount,
      @Param("transactionType") String transactionType,
      @Param("category") String category,
      @Param("merchantName") String merchantName,
      @Param("mcc") Short mcc,
      @Param("scheduledExpenseId") Integer scheduledExpenseId,
      @Param("externalTransactionId") String externalTransactionId);

  /** 지정 구간 PAYMENT-REFUND 순지출을 반환한다. */
  @Query(
      "select coalesce(sum(case when tx.transactionType = 'PAYMENT' then tx.amount else -tx.amount end), 0) from Transaction tx where tx.user.id = :userId and tx.transactionAt >= :from and tx.transactionAt < :to")
  long sumNetAmount(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /** 확정 PostgreSQL 함수의 월별 부트스트랩 표본만 projection으로 반환한다. */
  @Query(
      value =
          "SELECT bootstrap_eligible_spending AS amount FROM monthly_spending_window(:userId, :from, :to)",
      nativeQuery = true)
  List<MonthlySpendingAmount> monthlySpendingWindow(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /** 월별 계산 표본의 금액 projection이다. */
  interface MonthlySpendingAmount {
    long getAmount();
  }

  interface MonthlySummaryView {
    java.time.LocalDate getYearMonth();

    long getTotalVariableSpending();

    long getBootstrapEligibleSpending();
  }

  interface CategoryTotalView {
    String getCategory();

    long getTotal();
  }
}
