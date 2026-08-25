package com.dacon.core.plan;

import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 목표·계획 생성·조회·선택 유스케이스 계약이다. */
public interface PlanningService {
  PlanCreation createPlan(int userId, int goalId, String generationType, String requestId);

  List<PlanDetailResponse> planVersions(int userId, int goalId, String status);

  PlanDetailResponse planVersion(int userId, int planVersionId);

  ExplanationResponse explanation(int userId, int planVersionId);

  PlanDetailResponse select(int userId, int planVersionId, int optionId);

  PlanOptionResponse customOption(
      int userId, int planVersionId, long monthlySpending, String requestId);

  DashboardResponse dashboard(int userId);

  static void validateCalculation(JsonNode body, int horizonMonths) {
    CalculationResponseValidator.validate(body, horizonMonths);
  }
}
