package com.dacon.core.goal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 예정지출 조회와 저장을 Spring Data에 위임한다. */
public interface ScheduledExpenseRepository extends JpaRepository<ScheduledExpense, Integer> {
  /**
   * 예정지출 ID와 사용자 ID를 함께 조건으로 삼아 거래 연결 소유권을 확인한다.
   *
   * @param id 예정지출 ID
   * @param userId 예정지출 소유 사용자 ID
   * @return 예정지출 ID와 사용자 소유권이 모두 일치하면 {@code true}
   */
  boolean existsByIdAndUserId(int id, int userId);

  Optional<ScheduledExpense> findByIdAndUserId(int id, int userId);

  /**
   * 샘플 적재 전에 사용자의 기존 예정지출 존재 여부를 확인한다.
   *
   * @param userId 예정지출 소유 사용자 ID
   * @return 사용자가 소유한 예정지출이 하나라도 있으면 {@code true}
   */
  boolean existsByUserId(int userId);

  /**
   * 계획 입력에 포함할 예정지출을 상태와 포함 날짜 구간으로 조회한다.
   *
   * @param userId 예정지출 소유 사용자 ID
   * @param status 조회할 예정지출 상태
   * @param from 포함할 시작일
   * @param to 포함할 종료일
   * @return 사용자의 지정 상태·날짜 구간 예정지출을 예정일과 ID 오름차순으로 정렬한 목록
   */
  List<ScheduledExpense> findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
      int userId, String status, LocalDate from, LocalDate to);

  /**
   * 사용자의 예정지출별로 연결 거래 건수와 PAYMENT-REFUND 순액을 집계한다.
   *
   * @param userId 인증된 사용자 ID
   * @param status 조회할 상태, 전체 상태면 {@code null}
   * @return 예정일과 ID 순으로 정렬된 집계 projection
   */
  @Query(
      "select expense.id as id, expense.name as name, expense.amount as amount,"
          + " expense.scheduledDate as scheduledDate, expense.status as status, count(tx.id) as"
          + " matchedTransactionCount, coalesce(sum(case when tx.transactionType='PAYMENT' then"
          + " tx.amount else -tx.amount end),0) as matchedAmount from ScheduledExpense expense left"
          + " join Transaction tx on tx.scheduledExpense=expense where expense.user.id=:userId and"
          + " (:status is null or expense.status=:status) group by"
          + " expense.id,expense.name,expense.amount,expense.scheduledDate,expense.status order by"
          + " expense.scheduledDate,expense.id")
  List<ScheduledExpenseView> findViews(@Param("userId") int userId, @Param("status") String status);

  default Optional<ScheduledExpenseView> findView(int userId, int id) {
    return findViews(userId, null).stream().filter(value -> value.getId() == id).findFirst();
  }

  /** 예정지출 한 건과 연결 거래 집계를 읽기 위한 projection이다. */
  interface ScheduledExpenseView {
    /**
     * projection이 가리키는 예정지출을 식별한다.
     *
     * @return 예정지출 ID
     */
    int getId();

    /**
     * 예정지출 화면에 표시할 이름을 제공한다.
     *
     * @return 예정지출 이름
     */
    String getName();

    /**
     * 사용자가 입력한 확정 예정 금액을 제공한다.
     *
     * @return 예정 금액(원)
     */
    long getAmount();

    /**
     * 예정지출 정렬과 계획 반영 월의 기준일을 제공한다.
     *
     * @return 지출 예정일
     */
    LocalDate getScheduledDate();

    /**
     * 예정지출이 유효한 계획 입력인지 구분할 상태를 제공한다.
     *
     * @return 예정지출 상태
     */
    String getStatus();

    /**
     * 예정지출과 실제 거래가 몇 건 연결됐는지 제공한다.
     *
     * @return 이 예정지출에 연결된 거래 건수
     */
    long getMatchedTransactionCount();

    /**
     * 연결 거래의 지출과 환불을 상계한 실제 금액을 제공한다.
     *
     * @return 연결 거래의 PAYMENT-REFUND 순액(원)
     */
    long getMatchedAmount();
  }
}
