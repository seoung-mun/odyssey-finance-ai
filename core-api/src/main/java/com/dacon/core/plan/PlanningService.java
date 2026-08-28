package com.dacon.core.plan;

import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 인증된 사용자의 계획 생성, 조회, 선택과 대시보드 유스케이스 경계다. */
public interface PlanningService {
  /**
   * 목표의 확정 입력을 계산한 뒤 새 append-only 계획 버전을 저장하고, 계산 가능한 경우 설명 작업을 발행한다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 계산할 활성 목표 식별자
   * @param generationType {@code INITIAL} 또는 {@code USER_REQUESTED} 생성 종류
   * @param requestId 내부 계산 호출에 전달할 요청 식별자
   * @return 저장된 계획 상세와 계산 불가능 여부
   * @throws com.dacon.core.error.ApiException 소유 자원이 없거나 입력·이력·기간이 유효하지 않거나 계산 서비스가 실패한 경우
   */
  PlanCreation createPlan(int userId, int goalId, String generationType, String requestId);

  /**
   * 사용자 소유 목표의 계획 이력을 조회한다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 조회할 목표 식별자
   * @param status 제한할 계획 상태; 전체 상태를 조회하면 {@code null}
   * @return 최신 버전부터 정렬된 계획 상세 목록
   * @throws com.dacon.core.error.ApiException 목표가 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  List<PlanDetailResponse> planVersions(int userId, int goalId, String status);

  /**
   * 사용자 소유 계획 한 건을 조회한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planVersionId 조회할 계획 버전 식별자
   * @return 저장된 스냅샷, 옵션, 설명과 시뮬레이션 메타데이터
   * @throws com.dacon.core.error.ApiException 계획이 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  PlanDetailResponse planVersion(int userId, int planVersionId);

  /**
   * 사용자 소유 계획의 비동기 설명 상태를 조회한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planVersionId 설명 상태를 조회할 계획 버전 식별자
   * @return 현재 설명 상태와 생성 결과 메타데이터
   * @throws com.dacon.core.error.ApiException 계획이 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  ExplanationResponse explanation(int userId, int planVersionId);

  /**
   * 제안 계획의 옵션을 한 번 선택하고 같은 트랜잭션에서 기존 활성 계획을 교체한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planVersionId 선택할 {@code PROPOSED} 계획 식별자
   * @param optionId 해당 계획에 속한 옵션 식별자
   * @return 선택 후 {@code ACTIVE} 상태인 계획 상세
   * @throws com.dacon.core.error.ApiException 자원이 없거나 계획을 선택할 수 없는 경우
   */
  PlanDetailResponse select(int userId, int planVersionId, int optionId);

  /**
   * 기존 확정 입력으로 CUSTOM 옵션을 원격 계산하고 입력 동일성을 잠금 아래 확인한 뒤 추가한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planVersionId 옵션을 추가할 {@code PROPOSED} 계획 식별자
   * @param monthlySpending 사용자가 지정한 원 단위 월 유동지출
   * @param requestId 내부 계산 호출에 전달할 요청 식별자
   * @return 새로 저장된 CUSTOM 옵션
   * @throws com.dacon.core.error.ApiException 자원이 없거나 계획 상태·계산 입력·원격 응답이 유효하지 않은 경우
   */
  PlanOptionResponse customOption(
      int userId, int planVersionId, long monthlySpending, String requestId);

  /**
   * 사용자의 활성 목표와 계획, 선택 옵션, 대기 제안, 이번 달 진행률을 한 번에 조회한다.
   *
   * @param userId 조회할 사용자 식별자
   * @return 활성 목표가 없으면 모든 필드가 {@code null}인 대시보드, 있으면 현재 상태 묶음
   */
  DashboardResponse dashboard(int userId);

  /**
   * 내부 계산 응답을 저장 계약에 맞게 검증한다.
   *
   * @param body FastAPI 계산 응답 JSON
   * @param horizonMonths 입력으로 확정된 계산 개월 수
   * @throws com.dacon.core.error.ApiException 응답 graph가 저장 계약을 위반한 경우
   */
  static void validateCalculation(JsonNode body, int horizonMonths) {
    CalculationResponseValidator.validate(body, horizonMonths);
  }
}
