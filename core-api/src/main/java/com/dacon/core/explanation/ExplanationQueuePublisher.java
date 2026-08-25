package com.dacon.core.explanation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 설명 식별자만 Redis Stream에 발행하고 장애 시 설명 상태만 fallback한다. */
@Component
public class ExplanationQueuePublisher implements ExplanationQueuePort {
  public static final String FALLBACK_TEXT = "계획 수치는 정상적으로 준비됐습니다. 현재는 설명 대신 계획 상세를 확인해 주세요.";

  private final StringRedisTemplate redis;
  private final ExplanationStateService states;
  private final String stream;

  public ExplanationQueuePublisher(
      StringRedisTemplate redis,
      ExplanationStateService states,
      @Value("${app.explanation-stream}") String stream) {
    this.redis = redis;
    this.states = states;
    this.stream = stream;
  }

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
