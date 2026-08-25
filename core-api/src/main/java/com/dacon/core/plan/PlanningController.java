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

/** 계획 생성과 대시보드 HTTP API를 제공한다. */
@RestController
@RequestMapping("/api/v1")
public class PlanningController {
  private final PlanningService service;

  /** 계획 유스케이스 서비스를 받는다. */
  public PlanningController(PlanningService service) {
    this.service = service;
  }

  /** 사용자 소유 목표의 계획 버전 이력을 조회한다. */
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

  /** 사용자 소유 계획 상세를 조회한다. */
  @GetMapping("/plan-versions/{planVersionId}")
  public PlanDetailResponse planVersion(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int planVersionId) {
    return service.planVersion(userId(jwt), planVersionId);
  }

  /** 사용자 소유 계획의 설명 상태를 조회한다. */
  @GetMapping("/plan-versions/{planVersionId}/explanation")
  public ExplanationResponse explanation(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int planVersionId) {
    return service.explanation(userId(jwt), planVersionId);
  }

  /** PROPOSED 계획 옵션을 선택해 ACTIVE로 전환한다. */
  @PostMapping("/plan-versions/{planVersionId}/select-option")
  public PlanDetailResponse selectOption(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int planVersionId,
      @Valid @RequestBody OptionSelection input) {
    return service.select(userId(jwt), planVersionId, input.planOptionId());
  }

  /** 사용자 baseline으로 소유 계획에 CUSTOM 옵션을 추가한다. */
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

  /** 계산 호출 뒤 snapshot을 재검증해 새 계획 버전을 저장한다. */
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

  /** JWT 사용자의 현재 대시보드를 조회한다. */
  @GetMapping("/dashboard")
  public DashboardResponse dashboard(@AuthenticationPrincipal Jwt jwt) {
    return service.dashboard(userId(jwt));
  }

  /** 검증된 JWT subject를 사용자 ID로 변환한다. */
  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }
}
