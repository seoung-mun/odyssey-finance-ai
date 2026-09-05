package com.dacon.core.explanation;

import java.util.LinkedHashSet;
import java.util.Set;

/** 로컬 설명 모델에 전달할 확정 프롬프트와 숫자 검증 기준이다. */
public record ExplanationRequest(String prompt, Set<Long> allowedNumbers, String requestId) {
  public ExplanationRequest {
    if (prompt == null || prompt.isBlank() || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("prompt and requestId are required");
    }
    allowedNumbers = Set.copyOf(new LinkedHashSet<>(allowedNumbers));
  }
}
