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
  @Query(
      "select tx from Transaction tx where tx.user.id=:userId and (:from is null or"
          + " tx.transactionAt>=:from) and (:to is null or tx.transactionAt<:to) and (:category is"
          + " null or tx.category=:category) and tx.id<:cursor order by tx.id desc")
  List<Transaction> findPage(
      @Param("userId") int userId,
      @Param("from") OffsetDateTime from,
      @Param("to") OffsetDateTime to,
      @Param("category") String category,
      @Param("cursor") long cursor,
      Pageable pageable);

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
              + " \"totalVariableSpending\", bootstrap_eligible_spending AS"
              + " \"bootstrapEligibleSpending\" FROM monthly_spending_window(:userId,:from,:to)",
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
      "select tx.category as category, coalesce(sum(case when tx.transactionType='PAYMENT' then"
          + " tx.amount else -tx.amount end),0) as total from Transaction tx where"
          + " tx.user.id=:userId and tx.transactionAt>=:from and tx.transactionAt<:to group by"
          + " tx.category order by tx.category")
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
}
