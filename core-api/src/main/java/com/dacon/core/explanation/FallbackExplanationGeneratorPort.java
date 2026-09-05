package com.dacon.core.explanation;

/** 모델을 사용하지 못하는 프로필과 kill-switch의 즉시 fallback 구현체다. */
public final class FallbackExplanationGeneratorPort implements ExplanationGeneratorPort {
  @Override
  public ExplanationResult generate(ExplanationRequest request) {
    return ExplanationResult.fallback();
  }
}
