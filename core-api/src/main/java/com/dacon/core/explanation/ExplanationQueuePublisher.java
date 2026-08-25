package com.dacon.core.explanation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExplanationQueuePublisher {
  public static final String FALLBACK_TEXT = "계획 수치는 정상적으로 준비됐습니다. 현재는 설명 대신 계획 상세를 확인해 주세요.";

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;
  private final String stream;

  public ExplanationQueuePublisher(
      StringRedisTemplate redis,
      JdbcTemplate jdbc,
      @Value("${app.explanation-stream}") String stream) {
    this.redis = redis;
    this.jdbc = jdbc;
    this.stream = stream;
  }

  public boolean publish(long planVersionId, String inputHash, String promptVersion) {
    if (!inputHash.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("inputHash must be SHA-256 hex");
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
    } catch (RuntimeException exception) {
      jdbc.update(
          "UPDATE plan_versions SET explanation_status='FALLBACK', explanation_text=? WHERE id=? AND explanation_status='PENDING'",
          FALLBACK_TEXT,
          planVersionId);
      return false;
    }
  }
}
