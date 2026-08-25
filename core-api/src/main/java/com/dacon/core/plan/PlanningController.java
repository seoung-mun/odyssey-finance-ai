package com.dacon.core.plan;

import com.dacon.core.error.RequestIdFilter;
import com.dacon.core.plan.dto.PlanningDtos.CustomOptionRequest;
import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.OptionSelection;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT subject를 내부 사용자 식별자로 변환해 계획 생성·조회·선택 및 대시보드 유스케이스에 전달하는 HTTP 어댑터다.
 *
 * <p>입력 형식은 Bean Validation으로 제한하고, 계산 불가능 결과만 422 {@link ProblemDetail}로 직접 변환한다.
 */
@RestController
@RequestMapping("/api/v1")
public class PlanningController {
  private final PlanningService service;

  /**
   * 계획 유스케이스와 HTTP 요청을 연결한다.
   *
   * @param service 인증된 사용자 계획 유스케이스 서비스
   */
  public PlanningController(PlanningService service) {
    this.service = service;
  }

  /**
   * 사용자 소유 목표의 계획 버전 이력을 선택적 상태 필터로 조회한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param goalId 조회할 양수 목표 식별자
   * @param status 허용된 계획 상태; 전체 이력이 필요하면 {@code null}
   * @return 최신 버전부터 정렬된 계획 상세 목록
   */
  @GetMapping("/goals/{goalId}/plan-versions")
  public List<PlanDetailResponse> planVersions(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int goalId,
      @RequestParam(required = false)
          @Pattern(
              regexp = "PROPOSED|ACTIVE|SUPERSEDED|REJECTED|INFEASIBLE|STALE",
              message = "계획 상태를 확인해 주세요")
          String status) {
    return service.planVersions(userId(jwt), goalId, status);
  }

  /**
   * 사용자 소유 계획 한 건을 반환한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param planVersionId 조회할 양수 계획 버전 식별자
   * @return 사용자 소유 계획의 스냅샷, 옵션, 설명과 시뮬레이션 상세
   */
  @GetMapping("/plan-versions/{planVersionId}")
  public PlanDetailResponse planVersion(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int planVersionId) {
    return service.planVersion(userId(jwt), planVersionId);
  }

  /**
   * 계획의 비동기 설명 처리 상태를 반환한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param planVersionId 설명 상태를 조회할 양수 계획 버전 식별자
   * @return 현재 설명 상태와 생성 메타데이터
   */
  @GetMapping("/plan-versions/{planVersionId}/explanation")
  public ExplanationResponse explanation(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int planVersionId) {
    return service.explanation(userId(jwt), planVersionId);
  }

  /**
   * 제안 계획의 옵션을 선택해 계획을 활성화한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param planVersionId 선택할 양수 계획 버전 식별자
   * @param input 해당 계획에 속한 양수 옵션 식별자
   * @return 선택 결과가 반영된 활성 계획 상세
   */
  @PostMapping("/plan-versions/{planVersionId}/select-option")
  public PlanDetailResponse selectOption(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int planVersionId,
      @Valid @RequestBody OptionSelection input) {
    return service.select(userId(jwt), planVersionId, input.planOptionId());
  }

  /**
   * 사용자 자신의 확정 계획 입력으로 CUSTOM 옵션을 계산해 추가한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param planVersionId 옵션을 추가할 양수 계획 버전 식별자
   * @param input 0 이상인 원 단위 월 지출액
   * @param request 필터가 부여한 요청 식별자를 읽을 HTTP 요청
   * @return 저장된 CUSTOM 옵션과 월별 분위수 밴드
   */
  @PostMapping("/plan-versions/{planVersionId}/custom-option")
  public PlanOptionResponse customOption(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int planVersionId,
      @Valid @RequestBody CustomOptionRequest input,
      HttpServletRequest request) {
    return service.customOption(
        userId(jwt),
        planVersionId,
        input.monthlySpending(),
        (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
  }

  /**
   * 계산 호출 뒤 입력 스냅샷을 재검증해 새 계획 버전을 저장한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @param goalId 계산할 양수 목표 식별자
   * @param input 생성 종류; 본문이 없거나 종류가 없으면 {@code INITIAL}
   * @param request 내부 계산에 전달할 요청 식별자를 가진 HTTP 요청
   * @return 정상 계산이면 200 계획 상세, 계산 불가능이면 계획 식별자와 부족액을 포함한 422 문제 응답
   */
  @PostMapping("/goals/{goalId}/plan-versions")
  public ResponseEntity<Object> createPlan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int goalId,
      @Valid @RequestBody(required = false) PlanRequest input,
      HttpServletRequest request) {
    String generationType = input == null ? "INITIAL" : input.generationType();
    PlanCreation result =
        service.createPlan(
            userId(jwt),
            goalId,
            generationType,
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    if (!result.infeasible()) {
      return ResponseEntity.ok(result.detail());
    }
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY, "현재 조건으로 목표를 달성할 수 없습니다.");
    problem.setTitle("Unprocessable Entity");
    problem.setProperty("code", "PLAN_INFEASIBLE");
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    problem.setProperty("planVersionId", result.planVersionId());
    problem.setProperty("infeasibleReason", result.infeasibleReason());
    problem.setProperty("shortfallAmount", result.shortfallAmount());
    return ResponseEntity.unprocessableEntity().body(problem);
  }

  /**
   * JWT 사용자의 현재 대시보드를 반환한다.
   *
   * @param jwt 검증이 끝난 사용자 JWT
   * @return 활성 목표와 계획 진행 상태 묶음
   */
  @GetMapping("/dashboard")
  public DashboardResponse dashboard(@AuthenticationPrincipal Jwt jwt) {
    return service.dashboard(userId(jwt));
  }

  /**
   * 검증된 JWT subject를 내부 사용자 식별자로 변환한다.
   *
   * @param jwt 인증 필터가 검증한 JWT
   * @return subject의 10진수 사용자 식별자
   * @throws NumberFormatException subject가 정수 형식이 아닌 경우
   */
  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }
}
