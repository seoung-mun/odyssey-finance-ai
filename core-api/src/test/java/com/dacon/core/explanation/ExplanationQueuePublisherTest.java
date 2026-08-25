package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class ExplanationQueuePublisherTest {
  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  void jobContainsOnlyIdentifiersNeededByWorker() {
    var redis = mock(StringRedisTemplate.class);
    var operations = mock(StreamOperations.class);
    var jdbc = mock(JdbcTemplate.class);
    when(redis.opsForStream()).thenReturn(operations);
    when(operations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));
    var publisher = new ExplanationQueuePublisher(redis, jdbc, "jobs");
    var captor = ArgumentCaptor.forClass(MapRecord.class);

    assertThat(publisher.publish(7L, "a".repeat(64), "v1")).isTrue();
    verify(operations).add(captor.capture());
    assertThat((java.util.Map<String, String>) captor.getValue().getValue())
        .containsOnlyKeys("planVersionId", "inputHash", "promptVersion");
  }

  @SuppressWarnings("unchecked")
  @Test
  void redisFailureMarksOnlyExplanationAsFallback() {
    var redis = mock(StringRedisTemplate.class);
    var operations = mock(StreamOperations.class);
    var jdbc = mock(JdbcTemplate.class);
    org.mockito.Mockito.when(redis.opsForStream()).thenReturn(operations);
    doThrow(new RedisConnectionFailureException("down")).when(operations).add(any(MapRecord.class));
    var publisher = new ExplanationQueuePublisher(redis, jdbc, "jobs");

    var queued = publisher.publish(7L, "a".repeat(64), "v1");

    assertThat(queued).isFalse();
    verify(jdbc)
        .update(
            "UPDATE plan_versions SET explanation_status='FALLBACK', explanation_text=? WHERE id=? AND explanation_status='PENDING'",
            ExplanationQueuePublisher.FALLBACK_TEXT,
            7L);
  }
}
