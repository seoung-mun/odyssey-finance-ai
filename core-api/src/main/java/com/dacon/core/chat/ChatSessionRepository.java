package com.dacon.core.chat;

import com.dacon.core.chat.ChatDtos.ChatSelection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class ChatSessionRepository {
  static final Duration SESSION_TTL = Duration.ofMinutes(45);
  private static final Duration LOCK_TTL = Duration.ofSeconds(5);
  private static final int MAX_BYTES = 4096;
  private static final DefaultRedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public ChatSessionRepository(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  public boolean tryLock(String sessionId, String token) {
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(lockKey(sessionId), token, LOCK_TTL));
  }

  public void unlock(String sessionId, String token) {
    redis.execute(RELEASE, List.of(lockKey(sessionId)), token);
  }

  public Optional<SessionState> find(String sessionId) {
    String json = redis.opsForValue().get(sessionKey(sessionId));
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(json, SessionState.class));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("챗 세션 JSON을 읽을 수 없습니다.", exception);
    }
  }

  public void save(SessionState state) {
    SessionState bounded = state;
    String json = json(bounded);
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
      bounded = new SessionState(state.sessionId(), state.userId(), state.recentIntents(), null);
      json = json(bounded);
    }
    redis.opsForValue().set(sessionKey(state.sessionId()), json, SESSION_TTL);
  }

  private String json(SessionState state) {
    try {
      return mapper.writeValueAsString(state);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("챗 세션 JSON을 만들 수 없습니다.", exception);
    }
  }

  private String sessionKey(String sessionId) {
    return "chat:session:" + sessionId;
  }

  private String lockKey(String sessionId) {
    return "chat:lock:" + sessionId;
  }

  public record SessionState(
      String sessionId, int userId, List<ChatIntent> recentIntents, ChatSelection selection) {
    public SessionState {
      recentIntents = List.copyOf(recentIntents);
    }

    public SessionState append(ChatIntent intent, ChatSelection latestSelection) {
      List<ChatIntent> updated = new ArrayList<>(recentIntents);
      updated.add(intent);
      if (updated.size() > 6) {
        updated = new ArrayList<>(updated.subList(updated.size() - 6, updated.size()));
      }
      return new SessionState(
          sessionId, userId, updated, latestSelection == null ? selection : latestSelection);
    }
  }
}
