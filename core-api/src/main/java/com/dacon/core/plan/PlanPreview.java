package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;

/** DB 변경 전에 끝낸 계산 결과다. */
public record PlanPreview(
    PlanInput input, JsonNode calculation, String infeasibleReason, Long shortfallAmount) {
  public boolean infeasible() {
    return calculation == null;
  }
}
