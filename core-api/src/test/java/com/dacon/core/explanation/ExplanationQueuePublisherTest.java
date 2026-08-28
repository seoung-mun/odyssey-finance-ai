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

class ExplanationQueuePublisherTest {
  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  void jobContainsOnlyIdentifiersNeededByWorker() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations operations = mock(StreamOperations.class);
    ExplanationStateService states = mock(ExplanationStateService.class);
    when(redis.opsForStream()).thenReturn(operations);
    when(operations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));
    ExplanationQueuePublisher publisher = new ExplanationQueuePublisher(redis, states, "jobs");
    ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);

    assertThat(publisher.publish(7L, "a".repeat(64), "v1")).isTrue();
    verify(operations).add(captor.capture());
    assertThat((java.util.Map<String, String>) captor.getValue().getValue())
        .containsOnlyKeys("planVersionId", "inputHash", "promptVersion");
  }

  @SuppressWarnings("unchecked")
  @Test
  void redisFailureMarksOnlyExplanationAsFallback() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations operations = mock(StreamOperations.class);
    ExplanationStateService states = mock(ExplanationStateService.class);
    org.mockito.Mockito.when(redis.opsForStream()).thenReturn(operations);
    when(states.fallback(7L)).thenReturn(true);
    doThrow(new RedisConnectionFailureException("down")).when(operations).add(any(MapRecord.class));
    ExplanationQueuePublisher publisher = new ExplanationQueuePublisher(redis, states, "jobs");

    boolean queued = publisher.publish(7L, "a".repeat(64), "v1");

    assertThat(queued).isFalse();
    verify(states).fallback(7L);
  }

  @Test
  void nullInputIsNotTreatedAsRedisFailure() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ExplanationStateService states = mock(ExplanationStateService.class);
    ExplanationQueuePublisher publisher = new ExplanationQueuePublisher(redis, states, "jobs");

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> publisher.publish(7L, null, "v1"));
    org.mockito.Mockito.verifyNoInteractions(states);
  }

  @SuppressWarnings("unchecked")
  @Test
  void missingFallbackRowIsPropagated() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
    ExplanationStateService states = mock(ExplanationStateService.class);
    when(redis.opsForStream()).thenReturn(operations);
    doThrow(new RedisConnectionFailureException("down")).when(operations).add(any(MapRecord.class));
    when(states.fallback(7L)).thenReturn(false);
    ExplanationQueuePublisher publisher = new ExplanationQueuePublisher(redis, states, "jobs");

    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(() -> publisher.publish(7L, "a".repeat(64), "v1"));
  }
}
