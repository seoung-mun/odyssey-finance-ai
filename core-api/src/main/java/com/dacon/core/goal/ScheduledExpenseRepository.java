package com.dacon.core.goal;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 예정지출 조회와 저장을 Spring Data에 위임한다. */
public interface ScheduledExpenseRepository extends JpaRepository<ScheduledExpense, Integer> {
  boolean existsByIdAndUserId(int id, int userId);

  boolean existsByUserId(int userId);

  List<ScheduledExpense> findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
      int userId, String status, LocalDate from, LocalDate to);

  @Query(
      "select expense.id as id, expense.name as name, expense.amount as amount, expense.scheduledDate as scheduledDate, expense.status as status, count(tx.id) as matchedTransactionCount, coalesce(sum(case when tx.transactionType='PAYMENT' then tx.amount else -tx.amount end),0) as matchedAmount from ScheduledExpense expense left join Transaction tx on tx.scheduledExpense=expense where expense.user.id=:userId and (:status is null or expense.status=:status) group by expense.id,expense.name,expense.amount,expense.scheduledDate,expense.status order by expense.scheduledDate,expense.id")
  List<ScheduledExpenseView> findViews(@Param("userId") int userId, @Param("status") String status);

  interface ScheduledExpenseView {
    int getId();

    String getName();

    long getAmount();

    LocalDate getScheduledDate();

    String getStatus();

    long getMatchedTransactionCount();

    long getMatchedAmount();
  }
}
