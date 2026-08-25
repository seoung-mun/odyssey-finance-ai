package com.dacon.core.explanation;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis consumer group에서 신규 작업과 장시간 pending 작업을 가져와 설명 worker에 전달한다.
 *
 * <p>worker가 최종 상태를 저장했을 때만 ACK한다. Redis 오류나 최종 상태 저장 실패는 메시지를 pending으로 남겨 다음 poll 또는 reclaim에서 다시
 * 처리한다. FastAPI 오류는 worker가 fallback으로 마감하며, 파싱할 수 없는 poison 메시지는 경고 후 ACK한다.
 */
@Component
class ExplanationQueueConsumer {
  private static final Logger log = LoggerFactory.getLogger(ExplanationQueueConsumer.class);
  private final StringRedisTemplate redis;
  private final ExplanationWorker worker;
  private final ExplanationQueuePort publisher;
  private final ExplanationStateService states;
  private final String stream;
  private final String group;
  private final String consumerName;
  private final Duration reclaimIdle;
  private volatile boolean groupReady;

  @Autowired
  ExplanationQueueConsumer(
      StringRedisTemplate redis,
      ExplanationWorker worker,
      ExplanationQueuePort publisher,
      ExplanationStateService states,
      @Value("${app.explanation-stream}") String stream,
      @Value("${app.explanation-group:explanation-workers}") String group,
      @Value("${app.explanation-consumer:${HOSTNAME:core-api}}") String consumerName,
      @Value("${app.explanation-reclaim-idle:30s}") Duration reclaimIdle) {
    this.redis = redis;
    this.worker = worker;
    this.publisher = publisher;
    this.states = states;
    this.stream = stream;
    this.group = group;
    this.consumerName = consumerName;
    this.reclaimIdle = reclaimIdle;
  }

  /** consumer group을 준비하고 DB에 남은 미완료 작업을 Stream에 다시 발행한다. */
  @PostConstruct
  void initialize() {
    groupReady = createGroup();
    recoverPending();
  }

  /**
   * Stream이 없으면 함께 만들며 consumer group 생성을 시도한다.
   *
   * @return 생성 명령이 성공하면 {@code true}; 기존 group 또는 Redis 장애면 {@code false}
   */
  private boolean createGroup() {
    try {
      redis.execute(
          (RedisCallback<String>)
              connection ->
                  connection
                      .streamCommands()
                      .xGroupCreate(
                          stream.getBytes(StandardCharsets.UTF_8),
                          group,
                          ReadOffset.latest(),
                          true));
      return true;
    } catch (DataAccessException exception) {
      // group 존재와 Redis 일시 장애는 동일 명령 오류다. scheduled poll에서 재시도한다.
      return false;
    }
  }

  /** 신규 메시지를 최대 10개 읽어 처리하며 Redis 오류는 다음 주기까지 격리한다. */
  @Scheduled(fixedDelayString = "${app.explanation-poll-delay:1000}")
  void poll() {
    try {
      if (!groupReady) {
        createGroup();
      }
      List<MapRecord<String, Object, Object>> records =
          redis
              .opsForStream()
              .read(
                  Consumer.from(group, consumerName),
                  StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                  StreamOffset.create(stream, ReadOffset.lastConsumed()));
      groupReady = true;
      if (records != null) {
        records.forEach(this::consume);
      }
    } catch (RuntimeException exception) {
      // Redis 장애는 계산 결과를 건드리지 않고 다음 poll에서 재시도한다.
    }
  }

  /**
   * Stream 레코드를 작업 식별자로 변환해 worker에 전달한다.
   *
   * @param record Redis Stream에서 읽은 작업 레코드
   * @return 최종 처리 또는 poison ACK가 가능하면 {@code true}; 재시도가 필요하면 {@code false}
   */
  boolean consume(MapRecord<String, ?, ?> record) {
    Map<?, ?> value = record.getValue();
    long planVersionId;
    String inputHash;
    String promptVersion;
    try {
      planVersionId = Long.parseLong(String.valueOf(value.get("planVersionId")));
      inputHash = String.valueOf(value.get("inputHash"));
      promptVersion = String.valueOf(value.get("promptVersion"));
    } catch (RuntimeException exception) {
      log.warn(
          "operation=explanation.consume success=false error=poison recordId={}", record.getId());
      redis.opsForStream().acknowledge(stream, group, record.getId());
      return true;
    }
    try {
      if (!worker.process(planVersionId, inputHash, promptVersion)) {
        return false;
      }
      redis.opsForStream().acknowledge(stream, group, record.getId());
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  /** 설정된 idle 시간을 넘긴 pending 메시지를 현재 consumer로 claim해 다시 처리한다. */
  @Scheduled(fixedDelayString = "${app.explanation-reclaim-delay:30000}")
  void reclaim() {
    try {
      StreamOperations<String, Object, Object> operations = redis.opsForStream();
      PendingMessages pending = operations.pending(stream, group, Range.unbounded(), 10);
      if (pending == null) {
        return;
      }
      for (PendingMessage message : pending) {
        if (message.getElapsedTimeSinceLastDelivery().compareTo(reclaimIdle) < 0) {
          continue;
        }
        List<MapRecord<String, Object, Object>> claimed =
            operations.claim(stream, group, consumerName, reclaimIdle, message.getId());
        if (claimed != null) {
          claimed.forEach(this::consume);
        }
      }
    } catch (RuntimeException exception) {
      // pending은 Redis에 남으므로 다음 reclaim에서 다시 처리한다.
    }
  }

  /** DB에 남은 미완료 작업을 식별자 단위로 다시 발행해 재기동 중 작업 유실을 보완한다. */
  void recoverPending() {
    for (ExplanationStateService.PendingExplanation pending : states.pending()) {
      publisher.publish(pending.planVersionId(), pending.inputHash(), pending.promptVersion());
    }
  }
}
