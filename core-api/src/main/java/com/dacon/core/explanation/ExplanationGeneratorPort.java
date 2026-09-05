package com.dacon.core.explanation;

/** 확정된 계획 설명을 생성하는 로컬 AI 경계다. */
public interface ExplanationGeneratorPort {
  /**
   * 허용 숫자와 요청 식별자가 포함된 설명 요청을 처리한다.
   *
   * @param request 검증 가능한 설명 생성 입력
   * @return READY 또는 FALLBACK 최종 결과
   */
  ExplanationResult generate(ExplanationRequest request);
}
