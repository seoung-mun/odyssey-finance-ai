package com.dacon.core.explanation;

import com.dacon.core.plan.PlanVersion;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 설명 작업 snapshot과 상태 변경 대상 plan을 JPA로 조회한다. */
public interface ExplanationJobRepository extends JpaRepository<PlanVersion, Integer> {
  @Query(
      """
      select plan.id as planVersionId, plan.explanationStatus as status,
             simulation.inputHash as inputHash,
             plan.explanationPromptVersion as promptVersion,
             option.recommendedMonthlySpending as recommendedMonthlySpending,
             plan.currentAvgVariableSpending as currentAvgVariableSpending,
             plan.targetAmountSnapshot as targetAmount,
             plan.currentSavedSnapshot as currentSavedAmount,
             plan.asOfDate as asOfDate,
             plan.targetDateSnapshot as targetDate,
             option.simulationCoverage as simulationCoverage,
             option.aggressiveWarning as aggressiveWarning
        from SimulationRun simulation
        join simulation.planVersion plan
        join PlanOption option on option.planVersion = plan
       where plan.id = :planId and option.optionType = 'PRESET'
         and option.nominalLevel = 0.800
      """)
  Optional<ExplanationJobView> findJob(@Param("planId") long planId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from PlanVersion plan where plan.id = :planId")
  Optional<PlanVersion> findForUpdate(@Param("planId") long planId);

  @Query(
      """
      select plan.id as planVersionId, simulation.inputHash as inputHash,
             plan.explanationPromptVersion as promptVersion
        from SimulationRun simulation join simulation.planVersion plan
       where plan.explanationStatus in ('PENDING', 'PROCESSING')
      """)
  List<PendingExplanationView> findPending();

  interface ExplanationJobView {
    long getPlanVersionId();

    String getStatus();

    String getInputHash();

    String getPromptVersion();

    Long getRecommendedMonthlySpending();

    long getCurrentAvgVariableSpending();

    long getTargetAmount();

    long getCurrentSavedAmount();

    LocalDate getAsOfDate();

    LocalDate getTargetDate();

    BigDecimal getSimulationCoverage();

    Boolean getAggressiveWarning();
  }

  interface PendingExplanationView {
    long getPlanVersionId();

    String getInputHash();

    String getPromptVersion();
  }
}
