package com.dacon.core.plan;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계획 옵션 조회·저장과 선택 잠금을 Spring Data에 위임한다. */
public interface PlanOptionRepository extends JpaRepository<PlanOption, Integer> {
  List<PlanOption> findByPlanVersionIdOrderByNominalLevelAscIdAsc(int planVersionId);

  Optional<PlanOption> findByPlanVersionIdAndSelectedAtIsNotNull(int planVersionId);

  Optional<PlanOption> findByIdAndPlanVersionGoalUserId(int optionId, int userId);

  Optional<PlanOption> findByPlanVersionIdAndNominalLevel(
      int planVersionId, BigDecimal nominalLevel);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select option from PlanOption option where option.id = :optionId and option.planVersion.id = :planId")
  Optional<PlanOption> findForUpdate(@Param("planId") int planId, @Param("optionId") int optionId);
}
