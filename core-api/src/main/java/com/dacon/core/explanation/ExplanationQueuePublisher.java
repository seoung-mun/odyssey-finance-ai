package com.dacon.core.explanation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 설명 작업 식별자만 Redis Stream에 발행하는 어댑터다.
 *
 * <p>Redis 쓰기가 실패하면 계산 결과나 계획 상태를 되돌리지 않고 별도 DB 트랜잭션으로 설명 상태만 {@code FALLBACK}으로 전환한다.
 */
@Component
public class ExplanationQueuePublisher implements ExplanationQueuePort {
  /** Redis 장애 시 숫자를 포함하지 않고 제공하는 고정 설명 문구다. */
  public static final String FALLBACK_TEXT = "계획 수치는 정상적으로 준비됐습니다. 현재는 설명 대신 계획 상세를 확인해 주세요.";

  private final StringRedisTemplate redis;
  private final ExplanationStateService states;
  private final String stream;

  /**
   * Redis 연결과 fallback 상태 저장 서비스를 구성한다.
   *
   * @param redis 설명 작업을 기록할 Redis 접근 객체
   * @param states Redis 장애 시 설명 상태를 변경할 서비스
   * @param stream 작업을 발행할 Redis Stream 키
   */
  public ExplanationQueuePublisher(
      StringRedisTemplate redis,
      ExplanationStateService states,
      @Value("${app.explanation-stream}") String stream) {
    this.redis = redis;
    this.states = states;
    this.stream = stream;
  }

  /**
   * 계획 식별자, 입력 해시, 프롬프트 버전만 Stream 레코드로 발행한다.
   *
   * @param planVersionId 설명 대상 계획 버전의 양수 식별자
   * @param inputHash 소문자 16진수 64자로 표현한 입력 해시
   * @param promptVersion 비어 있지 않은 프롬프트 버전
   * @return 발행 성공 시 {@code true}; Redis 장애를 fallback으로 처리했으면 {@code false}
   * @throws IllegalArgumentException 작업 식별자가 유효하지 않은 경우
   * @throws IllegalStateException Redis 장애 뒤 fallback할 계획 행이 없는 경우
   */
  @Override
  public boolean publish(long planVersionId, String inputHash, String promptVersion) {
    if (planVersionId <= 0
        || inputHash == null
        || !inputHash.matches("^[0-9a-f]{64}$")
        || promptVersion == null
        || promptVersion.isBlank()) {
      throw new IllegalArgumentException("invalid explanation job");
    }
    try {
      redis
          .opsForStream()
          .add(
              StreamRecords.mapBacked(
                      Map.of(
                          "planVersionId", Long.toString(planVersionId),
                          "inputHash", inputHash,
                          "promptVersion", promptVersion))
                  .withStreamKey(stream));
      return true;
    } catch (DataAccessException exception) {
      if (!states.fallback(planVersionId)) {
        throw new IllegalStateException("fallback target plan version is missing");
      }
      return false;
    }
  }
}
