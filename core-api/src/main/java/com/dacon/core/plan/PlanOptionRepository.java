package com.dacon.core.plan;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계획 옵션 조회·저장과 선택 직렬화를 위한 잠금을 제공하는 Spring Data 저장소다. */
public interface PlanOptionRepository extends JpaRepository<PlanOption, Integer> {
  /**
   * 계획 상세에 표시할 옵션을 정렬해 찾는다.
   *
   * @param planVersionId 계획 버전 식별자
   * @return 명목 수준과 식별자 오름차순 옵션 목록
   */
  List<PlanOption> findByPlanVersionIdOrderByNominalLevelAscIdAsc(int planVersionId);

  /**
   * 활성 계획에 선택된 옵션을 찾는다.
   *
   * @param planVersionId 계획 버전 식별자
   * @return 선택 시각이 기록된 유일한 옵션
   */
  Optional<PlanOption> findByPlanVersionIdAndSelectedAtIsNotNull(int planVersionId);

  /**
   * 목표 관계를 경유해 사용자 소유 옵션을 찾는다.
   *
   * @param optionId 계획 옵션 식별자
   * @param userId 계획 목표를 소유한 사용자 식별자
   * @return 목표 관계로 사용자 소유권이 확인된 옵션
   */
  Optional<PlanOption> findByIdAndPlanVersionGoalUserId(int optionId, int userId);

  /**
   * 설명 기준 등 정확한 PRESET 수준이 필요한 조회를 수행한다.
   *
   * @param planVersionId 계획 버전 식별자
   * @param nominalLevel 찾을 PRESET 명목 수준
   * @return 계획 안에서 수준이 일치하는 옵션
   */
  Optional<PlanOption> findByPlanVersionIdAndNominalLevel(
      int planVersionId, BigDecimal nominalLevel);

  /**
   * 옵션 선택을 직렬화하기 위해 계획과 옵션이 일치하는 행을 비관적 쓰기 잠금으로 조회한다.
   *
   * @param planId 옵션이 속해야 하는 계획 버전 식별자
   * @param optionId 잠글 계획 옵션 식별자
   * @return 두 식별자의 관계가 일치하는 옵션
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select option from PlanOption option where option.id = :optionId and option.planVersion.id = :planId")
  Optional<PlanOption> findForUpdate(@Param("planId") int planId, @Param("optionId") int optionId);
}
