package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ExplanationQueueConsumerTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);

  private final ExplanationWorker worker = mock(ExplanationWorker.class);
  private final ExplanationQueuePort publisher = mock(ExplanationQueuePort.class);
  private final ExplanationStateService states = mock(ExplanationStateService.class);
  private final ExplanationQueueConsumer consumer =
      new ExplanationQueueConsumer(
          redis, worker, publisher, states, "jobs", "workers", "worker-1", Duration.ofSeconds(30));

  @Test
  void completedJobIsAcknowledged() {
    when(redis.opsForStream()).thenReturn(operations);
    when(worker.process(7L, "a".repeat(64), "v1")).thenReturn(true);
    MapRecord<String, String, String> record = record("1-0");

    assertThat(consumer.consume(record)).isTrue();
    verify(operations).acknowledge("jobs", "workers", RecordId.of("1-0"));
  }

  @Test
  void transientFailureLeavesMessagePending() {
    when(redis.opsForStream()).thenReturn(operations);
    when(worker.process(7L, "a".repeat(64), "v1"))
        .thenThrow(new IllegalStateException("db unavailable"));
    MapRecord<String, String, String> record = record("1-0");

    assertThat(consumer.consume(record)).isFalse();
    verify(operations, never()).acknowledge(any(), any(), any(RecordId[].class));
  }

  @Test
  void malformedRecordIsLeftPending() {
    when(redis.opsForStream()).thenReturn(operations);
    MapRecord<String, String, String> record =
        StreamRecords.newRecord()
            .in("jobs")
            .withId(RecordId.of("1-0"))
            .ofMap(Map.of("planVersionId", "not-a-number"));

    assertThat(consumer.consume(record)).isTrue();

    verify(worker, never()).process(org.mockito.ArgumentMatchers.anyLong(), any(), any());
    verify(operations).acknowledge("jobs", "workers", RecordId.of("1-0"));
  }

  @Test
  void initializationCreatesGroupWithMkStream() {
    when(redis.execute(any(RedisCallback.class))).thenReturn("OK");
    when(states.pending()).thenReturn(List.of());

    consumer.initialize();

    verify(redis).execute(any(RedisCallback.class));
  }

  @Test
  void pollRetriesGroupCreationAfterStartupRedisFailure() {
    when(redis.execute(any(RedisCallback.class)))
        .thenThrow(new RedisConnectionFailureException("down"))
        .thenReturn("OK");
    when(states.pending()).thenReturn(List.of());
    when(redis.opsForStream()).thenReturn(operations);

    consumer.initialize();
    consumer.poll();

    verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisCallback.class));
  }

  @Test
  void stalePendingJobIsClaimedForThisConsumer() {
    when(redis.opsForStream()).thenReturn(operations);
    PendingMessage pending =
        new PendingMessage(
            RecordId.of("1-0"), Consumer.from("workers", "dead-worker"), Duration.ofMinutes(1), 1);
    when(operations.pending("jobs", "workers", Range.unbounded(), 10))
        .thenReturn(new PendingMessages("workers", List.of(pending)));
    MapRecord<String, String, String> claimed = record("1-0");
    when(operations.claim(
            "jobs", "workers", "worker-1", Duration.ofSeconds(30), RecordId.of("1-0")))
        .thenReturn((List) List.of(claimed));
    when(worker.process(7L, "a".repeat(64), "v1")).thenReturn(true);

    consumer.reclaim();

    verify(operations).acknowledge("jobs", "workers", RecordId.of("1-0"));
  }

  @Test
  void startupRequeuesRecoverableDatabasePlansOnce() {
    when(states.pending())
        .thenReturn(
            List.of(new ExplanationStateService.PendingExplanation(7L, "a".repeat(64), "v1")));

    consumer.recoverPending();

    verify(publisher).publish(7L, "a".repeat(64), "v1");
  }

  @Test
  void startupSurvivesRedisConnectionFailure() {
    when(redis.opsForStream()).thenThrow(new RedisConnectionFailureException("down"));
    when(states.pending()).thenReturn(List.of());

    org.assertj.core.api.Assertions.assertThatCode(consumer::initialize).doesNotThrowAnyException();
  }

  @Test
  void startupSurvivesExistingConsumerGroup() {
    when(redis.opsForStream()).thenThrow(new RedisSystemException("group exists", null));
    when(states.pending()).thenReturn(List.of());

    org.assertj.core.api.Assertions.assertThatCode(consumer::initialize).doesNotThrowAnyException();
  }

  @Test
  void startupRecoverySurvivesRedisSystemFailure() {
    when(redis.opsForStream()).thenThrow(new RedisSystemException("down", null));
    when(states.pending())
        .thenReturn(
            List.of(new ExplanationStateService.PendingExplanation(7L, "a".repeat(64), "v1")));
    when(states.fallback(7L)).thenReturn(true);
    ExplanationQueueConsumer consumer =
        new ExplanationQueueConsumer(
            redis,
            worker,
            new ExplanationQueuePublisher(redis, states, "jobs"),
            states,
            "jobs",
            "workers",
            "worker-1",
            Duration.ofSeconds(30));

    org.assertj.core.api.Assertions.assertThatCode(consumer::initialize).doesNotThrowAnyException();

    verify(states).fallback(7L);
  }

  private MapRecord<String, String, String> record(String id) {
    return StreamRecords.newRecord()
        .in("jobs")
        .withId(RecordId.of(id))
        .ofMap(
            Map.of(
                "planVersionId", "7",
                "inputHash", "a".repeat(64),
                "promptVersion", "v1"));
  }
}
