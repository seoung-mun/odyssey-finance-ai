package com.dacon.core.transaction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 거래 영속화와 PostgreSQL 집계를 Spring Data 경계에 숨긴다. */
public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
  Optional<Transaction> findByIdAndUserId(long id, int userId);

  /**
   * 샘플 적재 전에 사용자의 기존 거래 존재 여부를 확인한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @return 사용자가 소유한 거래가 하나라도 있으면 {@code true}
   */
  boolean existsByUserId(int userId);

  /**
   * 사용자·반개구간 시각·카테고리 조건을 적용해 커서보다 작은 ID를 역순 조회한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @param category 정확히 일치시킬 카테고리
   * @param cursor 이 값보다 작은 거래 ID만 조회하는 상한
   * @param pageable 조회할 최대 행 수
   * @return 다음 페이지 존재 확인용 크기 제한이 적용된 거래 목록
   */
  default List<Transaction> findPage(
      int userId,
      OffsetDateTime from,
      OffsetDateTime to,
      String category,
      long cursor,
      Pageable pageable) {
    return findAll(
            (root, query, builder) -> {
              List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
              predicates.add(builder.equal(root.get("user").get("id"), userId));
              predicates.add(builder.lessThan(root.get("id"), cursor));
              if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("transactionAt"), from));
              }
              if (to != null) {
                predicates.add(builder.lessThan(root.get("transactionAt"), to));
              }
              if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
              }
              return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            },
            org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id")))
        .getContent();
  }

  /**
   * 확정 PostgreSQL 월 집계 함수로 사용자의 순 소비와 부트스트랩 대상 소비를 조회한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @return KST 월별 집계 projection
   */
  @Query(
      value =
          "SELECT year_month AS \"yearMonth\", total_variable_spending AS"
              + " \"totalVariableSpending\", gross_payment_spending AS \"grossPaymentSpending\","
              + " linked_refund_amount AS \"linkedRefundAmount\", unmatched_refund_inflow AS"
              + " \"unmatchedRefundInflow\", adjusted_consumption AS \"adjustedConsumption\","
              + " net_cash_flow AS \"netCashFlow\", bootstrap_eligible_spending AS"
              + " \"bootstrapEligibleSpending\" FROM monthly_spending_summary WHERE user_id=:userId"
              + " AND year_month >= (:from AT TIME ZONE 'Asia/Seoul')::date"
              + " AND year_month < (:to AT TIME ZONE 'Asia/Seoul')::date ORDER BY year_month",
      nativeQuery = true)
  List<MonthlySummaryView> monthlySummary(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /**
   * 지정 반개구간에서 사용자의 카테고리별 PAYMENT-REFUND 순액을 합산한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @return 카테고리 이름 순 순액 projection
   */
  @Query(
      value =
          "SELECT category AS \"category\", coalesce(sum(amount), 0) AS \"total\""
              + " FROM monthly_category_spending WHERE user_id=:userId"
              + " AND year_month >= (:from AT TIME ZONE 'Asia/Seoul')::date"
              + " AND year_month < (:to AT TIME ZONE 'Asia/Seoul')::date GROUP BY category"
              + " ORDER BY category",
      nativeQuery = true)
  List<CategoryTotalView> categoryTotals(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /**
   * 사용자·외부 거래 ID 부분 unique index 충돌을 무시해 한 건을 멱등 삽입한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param transactionAt offset 포함 거래 시각
   * @param amount 양의 거래 금액(원)
   * @param transactionType PAYMENT 또는 REFUND
   * @param category 소비 카테고리
   * @param merchantName 선택적 가맹점 이름
   * @param mcc 선택적 업종 코드
   * @param scheduledExpenseId 선택적 사용자 소유 예정지출 ID
   * @param externalTransactionId 사용자 범위 멱등 키
   * @return 삽입했으면 1, 같은 사용자의 멱등 키가 이미 있으면 0
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO transactions(user_id,transaction_at,amount,transaction_type,category,
            merchant_name,mcc,scheduled_expense_id,source_id,external_transaction_id)
          VALUES (:userId,:transactionAt,:amount,:transactionType,:category,:merchantName,:mcc,
            :scheduledExpenseId,:sourceId,:externalTransactionId)
          ON CONFLICT (user_id,source_id,external_transaction_id)
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
      @Param("sourceId") String sourceId,
      @Param("externalTransactionId") String externalTransactionId);

  default int insertIgnoringDuplicate(
      int userId,
      OffsetDateTime transactionAt,
      long amount,
      String transactionType,
      String category,
      String merchantName,
      Short mcc,
      Integer scheduledExpenseId,
      String externalTransactionId) {
    return insertIgnoringDuplicate(
        userId,
        transactionAt,
        amount,
        transactionType,
        category,
        merchantName,
        mcc,
        scheduledExpenseId,
        "MANUAL",
        externalTransactionId);
  }

  @Query(
      value =
          "SELECT id FROM transactions WHERE user_id=:userId AND source_id=:sourceId"
              + " AND external_transaction_id=:externalTransactionId",
      nativeQuery = true)
  Long findIdByUserIdAndSourceIdAndExternalTransactionId(
      @Param("userId") int userId,
      @Param("sourceId") String sourceId,
      @Param("externalTransactionId") String externalTransactionId);

  @Modifying
  @Query(
      value =
          "INSERT INTO"
              + " refund_allocations(user_id,refund_transaction_id,payment_transaction_id,payment_source_id,payment_external_transaction_id,amount)"
              + " VALUES (:userId,:refundId,"
              + ":paymentId,:paymentSourceId,:paymentExternalTransactionId,:amount)",
      nativeQuery = true)
  int insertRefundAllocation(
      @Param("userId") int userId,
      @Param("refundId") long refundId,
      @Param("paymentId") Long paymentId,
      @Param("paymentSourceId") String paymentSourceId,
      @Param("paymentExternalTransactionId") String paymentExternalTransactionId,
      @Param("amount") long amount);

  @Modifying
  @Query(
      value =
          "UPDATE refund_allocations SET payment_transaction_id=:paymentId WHERE user_id=:userId"
              + " AND payment_transaction_id IS NULL AND payment_source_id=:sourceId"
              + " AND payment_external_transaction_id=:externalTransactionId",
      nativeQuery = true)
  int resolvePendingAllocations(
      @Param("userId") int userId,
      @Param("paymentId") long paymentId,
      @Param("sourceId") String sourceId,
      @Param("externalTransactionId") String externalTransactionId);

  @Query(
      value =
          "SELECT id AS \"id\", payment_transaction_id AS \"paymentTransactionId\","
              + " payment_source_id AS \"paymentSourceId\", payment_external_transaction_id AS"
              + " \"paymentExternalTransactionId\", amount AS \"amount\", CASE WHEN"
              + " payment_transaction_id IS NULL THEN 'PENDING' ELSE 'RESOLVED' END AS \"status\""
              + " FROM refund_allocations WHERE user_id=:userId AND"
              + " (refund_transaction_id=:transactionId OR payment_transaction_id=:transactionId)"
              + " ORDER BY id",
      nativeQuery = true)
  List<RefundAllocationView> findAllocations(
      @Param("userId") int userId, @Param("transactionId") long transactionId);

  @Query(
      value =
          "SELECT amount FROM transactions WHERE user_id=:userId AND transaction_type='PAYMENT'"
              + " AND id<:transactionId AND (scheduled_expense_id IS NULL OR EXISTS (SELECT 1 FROM"
              + " scheduled_expenses se WHERE se.id=scheduled_expense_id AND se.status='CANCELLED'))"
              + " ORDER BY id DESC LIMIT 100",
      nativeQuery = true)
  List<Long> findPreviousVariablePaymentAmounts(
      @Param("userId") int userId, @Param("transactionId") long transactionId);

  /**
   * 지정 반개구간에서 사용자의 PAYMENT-REFUND 순지출을 반환한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @return 거래가 없으면 0인 순지출(원)
   */
  @Query(
      "select coalesce(sum(case when tx.transactionType = 'PAYMENT' then tx.amount else -tx.amount"
          + " end), 0) from Transaction tx where tx.user.id = :userId and tx.transactionAt >= :from"
          + " and tx.transactionAt < :to")
  long sumNetAmount(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /**
   * 확정 PostgreSQL 함수에서 유효한 예정지출 매칭분을 제외한 월별 부트스트랩 표본만 반환한다.
   *
   * @param userId 거래 소유 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @return 요청 구간의 월별 부트스트랩 대상 금액
   */
  @Query(
      value =
          "SELECT bootstrap_eligible_spending AS amount FROM monthly_spending_window(:userId,"
              + " :from, :to)",
      nativeQuery = true)
  List<MonthlySpendingAmount> monthlySpendingWindow(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to);

  /** 월별 계산 표본의 원 단위 금액 projection이다. */
  interface MonthlySpendingAmount {
    /**
     * 월별 부트스트랩 projection의 원 단위 표본을 제공한다.
     *
     * @return 해당 월의 부트스트랩 대상 소비액(원)
     */
    long getAmount();
  }

  /** 월 시작일과 두 종류의 순 소비액을 읽는 projection이다. */
  interface MonthlySummaryView {
    /**
     * 월별 집계가 나타내는 KST 달력 월을 제공한다.
     *
     * @return KST 기준 해당 월 1일
     */
    java.time.LocalDate getYearMonth();

    /**
     * 화면에 표시할 해당 월의 전체 순 유동지출을 제공한다.
     *
     * @return 예정지출 매칭분을 포함한 PAYMENT-REFUND 총 유동지출(원)
     */
    long getTotalVariableSpending();

    long getGrossPaymentSpending();

    long getLinkedRefundAmount();

    long getUnmatchedRefundInflow();

    long getAdjustedConsumption();

    long getNetCashFlow();

    /**
     * 몬테카를로 입력에 사용할 해당 월의 순 소비를 제공한다.
     *
     * @return 유효한 예정지출 매칭분을 제외한 부트스트랩 대상 소비(원)
     */
    long getBootstrapEligibleSpending();
  }

  /** 카테고리와 조회 구간 순액을 읽는 projection이다. */
  interface CategoryTotalView {
    /**
     * 집계 행이 나타내는 소비 카테고리를 제공한다.
     *
     * @return 소비 카테고리
     */
    String getCategory();

    /**
     * 카테고리 월평균의 분자가 되는 조회 구간 순액을 제공한다.
     *
     * @return 조회 구간의 PAYMENT-REFUND 순액(원)
     */
    long getTotal();
  }

  /** 한 거래와 관련된 환불 배분을 읽는 projection이다. */
  interface RefundAllocationView {
    long getId();

    Long getPaymentTransactionId();

    String getPaymentSourceId();

    String getPaymentExternalTransactionId();

    long getAmount();

    String getStatus();
  }
}
