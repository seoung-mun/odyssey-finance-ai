package com.dacon.core.explanation;

/** 계산 저장과 설명 실행을 분리하기 위해 설명 작업 식별자만 비동기 큐에 전달하는 경계다. */
public interface ExplanationQueuePort {
  /**
   * 확정 계획을 다시 조회할 수 있는 식별자 묶음을 큐에 발행한다.
   *
   * @param planVersionId 설명 대상 계획 버전 식별자
   * @param inputHash 저장된 시뮬레이션 입력과 작업의 동일성을 확인할 SHA-256 해시
   * @param promptVersion 저장된 계획이 사용할 설명 프롬프트 버전
   * @return 큐 발행 성공 시 {@code true}, 장애를 설명 fallback으로 흡수한 경우 {@code false}
   * @throws IllegalArgumentException 식별자, 해시 또는 프롬프트 버전이 유효하지 않은 경우
   * @throws IllegalStateException 큐 장애 뒤 fallback할 계획 행도 찾을 수 없는 경우
   */
  boolean publish(long planVersionId, String inputHash, String promptVersion);
}
