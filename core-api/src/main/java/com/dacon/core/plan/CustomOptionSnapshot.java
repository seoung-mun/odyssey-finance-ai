package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;

/** CUSTOM 계산 전후에 동일성을 확인할 확정 시뮬레이션 입력이다. */
public record CustomOptionSnapshot(
    int planVersionId,
    String status,
    String inputHash,
    JsonNode inputSnapshot,
    int horizonMonths) {}
