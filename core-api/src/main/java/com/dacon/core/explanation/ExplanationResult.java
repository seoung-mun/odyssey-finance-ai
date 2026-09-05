package com.dacon.core.explanation;

import java.time.Instant;
import java.util.List;

/** 숫자 검증을 마친 설명 생성의 최종 상태와 저장 메타데이터다. */
public record ExplanationResult(
    Status status,
    String text,
    String model,
    int retryCount,
    List<String> failedNumbers,
    Instant generatedAt) {
  /** 저장 가능한 설명의 최종 상태다. */
  public enum Status {
    READY,
    FALLBACK
  }

  public ExplanationResult {
    if (status == null || text == null || text.isBlank() || retryCount < 0 || retryCount > 2) {
      throw new IllegalArgumentException("invalid explanation result");
    }
    failedNumbers = List.copyOf(failedNumbers == null ? List.of() : failedNumbers);
    generatedAt = generatedAt == null ? Instant.now() : generatedAt;
  }

  /** 숫자 없는 고정 문구로 즉시 fallback 결과를 만든다. */
  public static ExplanationResult fallback() {
    return new ExplanationResult(
        Status.FALLBACK,
        ExplanationQueuePublisher.FALLBACK_TEXT,
        null,
        0,
        List.of(),
        Instant.now());
  }
}
