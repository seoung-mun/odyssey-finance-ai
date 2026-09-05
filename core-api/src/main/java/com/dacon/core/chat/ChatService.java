package com.dacon.core.chat;

import com.dacon.core.chat.ChatDtos.ChatRequest;
import com.dacon.core.chat.ChatDtos.ChatResponse;
import com.dacon.core.chat.ChatSessionRepository.SessionState;
import com.dacon.core.error.ApiException;
import com.dacon.core.savings.SavingsDtos.RecommendationListResponse;
import com.dacon.core.savings.SavingsRecommendationService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  static final String UNKNOWN_MESSAGE = "요청을 이해하지 못했습니다. 적금 추천, 계획 현황, 소비 요약처럼 원하는 작업을 말씀해 주세요.";
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final IntentClassifierPort classifier;
  private final ChatSessionRepository sessions;
  private final SavingsRecommendationService savings;

  public ChatService(
      IntentClassifierPort classifier,
      ChatSessionRepository sessions,
      SavingsRecommendationService savings) {
    this.classifier = classifier;
    this.sessions = sessions;
    this.savings = savings;
  }

  public ChatResponse respond(int userId, ChatRequest request) {
    String sessionId = sessionId(request.sessionId());
    String lockToken = UUID.randomUUID().toString();
    boolean locked;
    try {
      locked = sessions.tryLock(sessionId, lockToken);
    } catch (RuntimeException exception) {
      log.warn("Redis 장애로 챗 요청을 stateless fallback으로 처리합니다.");
      return response(
          userId, sessionId, "STATELESS_FALLBACK", classifier.classify(request.message()));
    }
    if (!locked) {
      throw new ApiException(HttpStatus.CONFLICT, "CHAT_SESSION_BUSY", "같은 대화의 이전 요청을 처리하고 있습니다.");
    }
    try {
      SessionState state;
      try {
        state =
            sessions
                .find(sessionId)
                .map(existing -> owned(existing, userId))
                .orElseGet(() -> new SessionState(sessionId, userId, List.of(), null));
      } catch (ApiException exception) {
        throw exception;
      } catch (RuntimeException exception) {
        log.warn("Redis 장애로 챗 요청을 stateless fallback으로 처리합니다.");
        return response(
            userId, sessionId, "STATELESS_FALLBACK", classifier.classify(request.message()));
      }
      ChatIntent intent = classifier.classify(request.message());
      try {
        sessions.save(state.append(intent, request.selection()));
      } catch (RuntimeException exception) {
        log.warn("Redis 장애로 챗 요청을 stateless fallback으로 처리합니다.");
        return response(userId, sessionId, "STATELESS_FALLBACK", intent);
      }
      return response(userId, sessionId, "STATEFUL", intent);
    } finally {
      try {
        sessions.unlock(sessionId, lockToken);
      } catch (RuntimeException exception) {
        log.warn("챗 세션 잠금 해제 중 Redis 연결이 끊겼습니다.");
      }
    }
  }

  private SessionState owned(SessionState state, int userId) {
    if (state.userId() != userId) {
      throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 대화 세션이 없습니다.");
    }
    return state;
  }

  private ChatResponse response(int userId, String sessionId, String mode, ChatIntent intent) {
    RecommendationListResponse recommendations =
        intent == ChatIntent.SAVINGS_RECOMMENDATION ? savings.recommendations(userId) : null;
    return new ChatResponse(sessionId, intent, mode, message(intent), recommendations);
  }

  private String message(ChatIntent intent) {
    return switch (intent) {
      case SAVINGS_RECOMMENDATION -> "현재 계획으로 가입 가능한 적금 추천을 확인했습니다.";
      case SAVINGS_WHAT_IF -> "상품과 옵션, 적용할 우대조건을 선택하면 예상 결과를 확인할 수 있습니다.";
      case PLAN_STATUS -> "대시보드에서 현재 목표와 선택한 계획 현황을 확인해 주세요.";
      case SPENDING_SUMMARY -> "소비 요약에서 이번 달 지출과 카테고리별 내역을 확인해 주세요.";
      case REPLAN_GUIDE -> "대시보드의 재계획 제안에서 변경 사유와 선택지를 확인해 주세요.";
      case POLICY_SEARCH -> "정책 검색에서 목표에 맞는 주거 지원 정책을 확인해 주세요.";
      case HELP -> "적금 추천, 적금 조건 비교, 계획 현황, 소비 요약, 재계획, 정책 검색을 도와드릴 수 있습니다.";
      case UNKNOWN -> UNKNOWN_MESSAGE;
    };
  }

  private String sessionId(String requested) {
    if (requested == null || requested.isBlank()) {
      return UUID.randomUUID().toString();
    }
    try {
      return UUID.fromString(requested).toString();
    } catch (IllegalArgumentException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_SESSION", "대화 세션 식별자를 확인해 주세요.");
    }
  }
}
