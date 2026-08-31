package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목표 잠금, 계획 graph 저장, 옵션 선택을 짧은 DB 트랜잭션으로 수행한다.
 *
 * <p>FastAPI 호출은 이 서비스에 들어오기 전에 끝나며, 저장 직전 잠금 아래 다시 만든 입력 스냅샷이 원격 계산 전 값과 다르면 전체 저장을 거부한다.
 */
@Service
public class PlanningCommandService {
  private final FinancialGoalRepository goals;
  private final PlanVersionRepository plans;
  private final SimulationRunRepository simulations;
  private final PlanOptionRepository options;
  private final PlanBandRepository bands;
  private final ReplanEventRepository replanEvents;
  private final PlanningQueryService queries;
  private final ObjectMapper mapper;

  /**
   * 계획 graph를 구성하는 저장소와 스냅샷 재조회 협력 객체를 받는다.
   *
   * @param goals 목표 소유권 재확인 저장소
   * @param plans 계획 버전 저장 및 잠금 저장소
   * @param simulations 계산 입력·결과 저장소
   * @param options 계획 옵션 저장 및 잠금 저장소
   * @param bands 월별 분위수 밴드 저장소
   * @param queries 잠금 아래 입력을 재조립할 조회 서비스
   * @param mapper JSON 기본값 생성에 사용할 매퍼
   */
  public PlanningCommandService(
      FinancialGoalRepository goals,
      PlanVersionRepository plans,
      SimulationRunRepository simulations,
      PlanOptionRepository options,
      PlanBandRepository bands,
      ReplanEventRepository replanEvents,
      PlanningQueryService queries,
      ObjectMapper mapper) {
    this.goals = goals;
    this.plans = plans;
    this.simulations = simulations;
    this.options = options;
    this.bands = bands;
    this.replanEvents = replanEvents;
    this.queries = queries;
    this.mapper = mapper;
  }

  /**
   * 계산 전후 입력 동일성을 확인하고 계획, 시뮬레이션, 옵션과 밴드를 한 트랜잭션에 저장한다.
   *
   * <p>같은 목표의 기존 {@code PROPOSED} 계획은 새 행 삽입 전에 {@code STALE}로 바꾼다. 계산 결과가 {@code null}이면 {@code
   * INFEASIBLE} 계획만 저장한다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 계획을 추가할 금융 목표 식별자
   * @param original FastAPI 호출 전에 읽은 입력 스냅샷
   * @param calculation 검증된 FastAPI 계산 JSON; 계산 불가능이면 {@code null}
   * @param generationType 최초 생성 또는 사용자 재계획 종류
   * @param infeasibleReason 계산 불가능 사유; 정상 계산이면 {@code null}
   * @return 저장된 계획 식별자와 설명 발행용 입력 해시
   * @throws ApiException 입력이 바뀌었거나 사용자 소유 목표가 사라진 경우
   */
  @Transactional
  public SavedPlan save(
      int userId,
      int goalId,
      PlanInput original,
      JsonNode calculation,
      String generationType,
      String infeasibleReason) {
    PlanInput current = queries.readPlanInputForUpdate(userId, goalId);
    if (!original.equals(current)) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }
    plans
        .findByGoalIdAndStatusOrderByVersionNoDesc(goalId, "PROPOSED")
        .forEach(PlanVersion::markStale);
    // Hibernate는 flush 시 update보다 insert를 먼저 내보낸다. flush 없이 두면 아래 INSERT가 위
    // STALE UPDATE보다 먼저 DB에 도달해 uq_plan_version_proposed_per_goal(goal당 PROPOSED 1개)을
    // 순간적으로 위반한다. select()의 supersede 처리와 동일하게 명시적으로 순서를 강제한다.
    plans.flush();
    FinancialGoal goal =
        goals
            .findByIdAndUserId(goalId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    int version = plans.findMaxVersionNo(goalId) + 1;
    PlanVersion plan =
        new PlanVersion(
            goal,
            version,
            generationType,
            calculation == null ? "INFEASIBLE" : "PROPOSED",
            LocalDate.now(PlanningQueryService.KST),
            original.monthlyIncome(),
            original.monthlyFixedCost(),
            original.targetAmount(),
            original.currentSavedAmount(),
            original.targetDate(),
            original.availableVariableBudget(),
            original.currentAverage(),
            original.policySnapshot(),
            calculation == null ? null : "v1",
            infeasibleReason,
            mapper.createArrayNode());
    plans.saveAndFlush(plan);
    if (calculation == null) {
      return new SavedPlan(plan.id(), null);
    }
    JsonNode simulation = calculation.path("simulation");
    simulations.save(
        new SimulationRun(
            plan,
            simulation.path("method").asText(),
            simulation.path("nPaths").asInt(),
            simulation.path("randomSeed").asLong(),
            simulation.path("inputSnapshot"),
            simulation.path("inputHash").asText(),
            simulation.path("engineVersion").asText(),
            simulation.path("resultSummary")));
    List<PlanOption> savedOptions = new ArrayList<>();
    for (JsonNode option : calculation.path("options")) {
      savedOptions.add(options.saveAndFlush(new PlanOption(plan, option)));
    }
    for (JsonNode band : calculation.path("percentileBands")) {
      bands.save(new PlanBand(savedOptions.get(band.path("optionIndex").asInt()), band));
    }
    return new SavedPlan(plan.id(), simulation.path("inputHash").asText());
  }

  /**
   * 계획과 옵션을 잠근 뒤 하나의 제안을 활성화하고 이전 활성 계획을 교체한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planId 선택할 {@code PROPOSED} 계획 식별자
   * @param optionId 해당 계획 안에서 선택할 옵션 식별자
   * @return 활성화된 계획 버전 식별자
   * @throws ApiException 자원이 없거나 옵션이 이미 선택됐거나 계획 상태가 선택 불가능한 경우
   */
  @Transactional
  public int select(int userId, int planId, int optionId) {
    PlanVersion plan =
        plans
            .findOwnedForUpdate(userId, planId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    PlanOption option =
        options
            .findForUpdate(planId, optionId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if (option.selectedAt() != null) {
      throw new ApiException(HttpStatus.CONFLICT, "OPTION_ALREADY_SELECTED", "이미 선택된 옵션입니다.");
    }
    if (!"PROPOSED".equals(plan.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_SELECTABLE", "선택할 수 없는 계획입니다.");
    }
    replanEvents
        .findByProposedPlanVersionId(planId)
        .filter(event -> !"ACCEPT_NEW_PLAN".equals(event.userDecision()))
        .ifPresent(
            event -> {
              throw new ApiException(
                  HttpStatus.CONFLICT, "REPLAN_ACCEPT_REQUIRED", "재계획을 먼저 수락해 주세요.");
            });
    plans
        .findFirstByGoalIdAndStatusOrderByVersionNoDesc(plan.goal().id(), "ACTIVE")
        .ifPresent(PlanVersion::supersede);
    plans.flush();
    Instant now = Instant.now();
    option.select(now);
    plan.activate(now);
    return plan.id();
  }

  /**
   * CUSTOM 원격 계산 중 계획 스냅샷이 바뀌지 않았는지 잠금 아래 확인하고 옵션과 밴드를 저장한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param original 원격 호출 전에 읽은 계획·시뮬레이션 스냅샷
   * @param calculation 검증된 단일 CUSTOM 옵션 계산 JSON
   * @return 새로 저장된 계획 옵션 식별자
   * @throws ApiException 계획이 없거나 더 이상 제안 상태가 아니거나 계산 입력이 바뀐 경우
   */
  @Transactional
  public int saveCustomOption(int userId, CustomOptionSnapshot original, JsonNode calculation) {
    PlanVersion plan =
        plans
            .findOwnedForUpdate(userId, original.planVersionId())
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if (!"PROPOSED".equals(plan.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_SELECTABLE", "선택할 수 없는 계획입니다.");
    }
    SimulationRun current =
        simulations
            .findByPlanVersionId(plan.id())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "계획 계산 결과가 없습니다."));
    if (!original.inputHash().equals(current.inputHash())
        || !original.inputSnapshot().equals(current.inputSnapshot())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }
    PlanOption option = options.saveAndFlush(new PlanOption(plan, calculation.path("option")));
    for (JsonNode band : calculation.path("percentileBands")) {
      bands.save(new PlanBand(option, band));
    }
    return option.id();
  }
}
