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

/** 설명 작업 스냅샷과 상태 변경 대상 계획을 조회하는 Spring Data 저장소다. */
public interface ExplanationJobRepository extends JpaRepository<PlanVersion, Integer> {
  /**
   * 시뮬레이션과 명목 수준 0.800 PRESET 옵션을 결합해 설명 입력을 조회한다.
   *
   * @param planId 설명 대상 계획 버전 식별자
   * @return 모든 조인 대상이 존재할 때의 읽기 전용 투영
   */
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

  /**
   * 설명 상태를 원자적으로 전이할 계획 행을 비관적 쓰기 잠금으로 조회한다.
   *
   * @param planId 잠글 계획 버전 식별자
   * @return 해당 계획 행
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from PlanVersion plan where plan.id = :planId")
  Optional<PlanVersion> findForUpdate(@Param("planId") long planId);

  /**
   * 애플리케이션 재기동 뒤 큐에 복구할 미완료 설명 작업을 조회한다.
   *
   * @return 상태가 {@code PENDING} 또는 {@code PROCESSING}인 작업 식별자 목록
   */
  @Query(
      """
      select plan.id as planVersionId, simulation.inputHash as inputHash,
             plan.explanationPromptVersion as promptVersion
        from SimulationRun simulation join simulation.planVersion plan
       where plan.explanationStatus in ('PENDING', 'PROCESSING')
      """)
  List<PendingExplanationView> findPending();

  /** 설명 요청에 필요한 계획·시뮬레이션·기준 옵션 필드의 읽기 전용 투영이다. */
  interface ExplanationJobView {
    /** {@return 계획 버전 식별자} */
    long getPlanVersionId();

    /** {@return 현재 설명 상태} */
    String getStatus();

    /** {@return 계산 입력 SHA-256 해시} */
    String getInputHash();

    /** {@return 설명 프롬프트 버전} */
    String getPromptVersion();

    /** {@return 기준 PRESET 옵션의 권장 월 지출액} */
    Long getRecommendedMonthlySpending();

    /** {@return 계산 당시 월평균 유동지출} */
    long getCurrentAvgVariableSpending();

    /** {@return 계산 당시 목표 금액} */
    long getTargetAmount();

    /** {@return 계산 당시 저축 금액} */
    long getCurrentSavedAmount();

    /** {@return 계획 계산 기준일} */
    LocalDate getAsOfDate();

    /** {@return 목표 달성 예정일} */
    LocalDate getTargetDate();

    /** {@return 기준 옵션의 시뮬레이션 달성 비율} */
    BigDecimal getSimulationCoverage();

    /** {@return 기준 옵션의 과도한 절감 경고 여부} */
    Boolean getAggressiveWarning();
  }

  /** 재발행에 필요한 최소 작업 필드의 읽기 전용 투영이다. */
  interface PendingExplanationView {
    /** {@return 계획 버전 식별자} */
    long getPlanVersionId();

    /** {@return 계산 입력 SHA-256 해시} */
    String getInputHash();

    /** {@return 설명 프롬프트 버전} */
    String getPromptVersion();
  }
}
