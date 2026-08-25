package com.dacon.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Spring 유스케이스가 내부 FastAPI의 계산·설명 기능을 호출하는 경계다.
 *
 * <p>구현체는 DB 상태를 변경하지 않으며, 호출자는 반환 JSON을 검증하고 필요한 영속화를 별도 트랜잭션에서 수행한다.
 */
public interface AnalysisServicePort {
  /**
   * 확정된 계산 입력으로 몬테카를로 계획을 동기 계산한다.
   *
   * @param json 내부 계산 API 계약을 따르는 JSON 문자열
   * @param requestId 서비스 간 추적에 사용할 요청 식별자
   * @return 시뮬레이션 메타데이터, 지출 하한, 선택지와 분위수 밴드를 포함한 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 없거나 요청 식별자가 공백인 경우
   * @throws com.dacon.core.error.ApiException 계산 입력이 거부되거나 내부 서비스가 응답하지 않는 경우
   */
  JsonNode simulate(String json, String requestId);

  /**
   * 확정 계획 JSON을 자연어 설명으로 변환한다.
   *
   * @param json 허용 숫자와 확정 계획을 담은 내부 설명 API 요청 JSON
   * @param requestId 서비스 간 추적에 사용할 요청 식별자
   * @return {@code READY} 또는 {@code FALLBACK} 상태의 검증 가능한 설명 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 없거나 요청 식별자가 공백인 경우
   * @throws com.dacon.core.error.ApiException 호출 실패 또는 응답 형식 위반으로 설명 결과를 신뢰할 수 없는 경우
   */
  JsonNode generateExplanation(String json, String requestId);

  /**
   * 기존 계획의 확정 입력에 사용자가 지정한 월 지출액을 적용해 CUSTOM 선택지를 계산한다.
   *
   * @param json 기존 입력 스냅샷과 사용자 지정 월 지출액을 담은 JSON 문자열
   * @param requestId 서비스 간 추적에 사용할 요청 식별자
   * @return 해석된 지출 하한, CUSTOM 선택지와 분위수 밴드를 포함한 JSON 객체
   * @throws IllegalArgumentException 입력 JSON 또는 요청 식별자가 없거나 요청 식별자가 공백인 경우
   * @throws com.dacon.core.error.ApiException 입력 거부, 호출 실패 또는 응답 형식 위반이 발생한 경우
   */
  JsonNode customOption(String json, String requestId);
}
