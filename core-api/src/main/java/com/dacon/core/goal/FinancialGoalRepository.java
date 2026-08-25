package com.dacon.core.goal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 목표 조회·저장과 명시적 잠금을 Spring Data에 위임한다. */
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Integer> {
  List<FinancialGoal> findByUserIdOrderByCreatedAtDesc(int userId);

  List<FinancialGoal> findByUserIdAndStatusOrderByCreatedAtDesc(int userId, String status);

  Optional<FinancialGoal> findByIdAndUserId(int id, int userId);

  Optional<FinancialGoal> findFirstByUserIdAndStatus(int userId, String status);

  boolean existsByUserIdAndStatus(int userId, String status);

  /** 계산 결과 저장 전 목표를 잠그고 소유권·상태를 함께 확인한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select goal from FinancialGoal goal where goal.id = :goalId and goal.user.id = :userId and goal.status = 'ACTIVE'")
  Optional<FinancialGoal> findActiveForUpdate(
      @Param("userId") int userId, @Param("goalId") int goalId);
}
