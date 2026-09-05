package com.dacon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.chat.ChatDtos.ChatRequest;
import com.dacon.core.chat.ChatDtos.ChatSelection;
import com.dacon.core.chat.ChatSessionRepository.SessionState;
import com.dacon.core.error.ApiException;
import com.dacon.core.savings.SavingsRecommendationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatServiceTest {
  private final IntentClassifierPort classifier = mock(IntentClassifierPort.class);
  private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
  private final SavingsRecommendationService savings = mock(SavingsRecommendationService.class);
  private final ChatService service = new ChatService(classifier, sessions, savings);

  @Test
  void storesOnlyIntentAndStructuredSelection() {
    String sessionId = UUID.randomUUID().toString();
    ChatSelection selection = new ChatSelection(1L, 2L, List.of(3L));
    when(sessions.tryLock(any(), any())).thenReturn(true);
    when(sessions.find(sessionId)).thenReturn(Optional.empty());
    when(classifier.classify("적금 추천해 줘")).thenReturn(ChatIntent.HELP);

    var result = service.respond(7, new ChatRequest(sessionId, "적금 추천해 줘", selection));

    ArgumentCaptor<SessionState> state = ArgumentCaptor.forClass(SessionState.class);
    verify(sessions).save(state.capture());
    assertThat(state.getValue().userId()).isEqualTo(7);
    assertThat(state.getValue().recentIntents()).containsExactly(ChatIntent.HELP);
    assertThat(state.getValue().selection()).isEqualTo(selection);
    assertThat(result.sessionMode()).isEqualTo("STATEFUL");
  }

  @Test
  void redisFailureReturnsStatelessFallbackWithCurrentIntent() {
    when(sessions.tryLock(any(), any())).thenThrow(new IllegalStateException("redis down"));
    when(classifier.classify("계획 현황")).thenReturn(ChatIntent.PLAN_STATUS);

    var result = service.respond(7, new ChatRequest(null, "계획 현황", null));

    assertThat(result.sessionMode()).isEqualTo("STATELESS_FALLBACK");
    assertThat(result.intent()).isEqualTo(ChatIntent.PLAN_STATUS);
  }

  @Test
  void overlappingRequestReturnsStableConflict() {
    when(sessions.tryLock(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.respond(7, new ChatRequest(null, "도움말", null)))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("CHAT_SESSION_BUSY");
  }

  @Test
  void unknownUsesTheExactContractMessage() {
    when(sessions.tryLock(any(), any())).thenReturn(true);
    when(sessions.find(any())).thenReturn(Optional.empty());
    when(classifier.classify("모호한 말")).thenReturn(ChatIntent.UNKNOWN);

    var result = service.respond(7, new ChatRequest(null, "모호한 말", null));

    assertThat(result.message()).isEqualTo(ChatService.UNKNOWN_MESSAGE);
  }
}
