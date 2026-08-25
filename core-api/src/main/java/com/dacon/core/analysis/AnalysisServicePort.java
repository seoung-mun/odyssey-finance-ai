package com.dacon.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/** FastAPI 계산·설명 호출을 Spring 유스케이스에서 분리하는 port다. */
public interface AnalysisServicePort {
  JsonNode simulate(String json, String requestId);

  JsonNode generateExplanation(String json, String requestId);

  JsonNode customOption(String json, String requestId);
}
