package com.dacon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SpringAiIntentClassifierTest {
  private final ChatClient chatClient = mock(ChatClient.class);
  private final ChatClient.ChatClientRequestSpec request =
      mock(ChatClient.ChatClientRequestSpec.class);
  private final ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

  @BeforeEach
  void chain() {
    when(chatClient.prompt()).thenReturn(request);
    when(request.user(anyString())).thenReturn(request);
    when(request.call()).thenReturn(response);
  }

  @Test
  void acceptsOnlyAnExactIntentEnum() {
    when(response.content()).thenReturn("PLAN_STATUS");
    var classifier =
        new SpringAiIntentClassifier(
            chatClient, Duration.ofSeconds(1), new DeterministicIntentClassifier());

    assertThat(classifier.classify("계획 현황 알려줘")).isEqualTo(ChatIntent.PLAN_STATUS);
  }

  @Test
  void invalidModelOutputFallsBackDeterministically() {
    when(response.content()).thenReturn("계획은 PLAN_STATUS 입니다");
    var classifier =
        new SpringAiIntentClassifier(
            chatClient, Duration.ofSeconds(1), new DeterministicIntentClassifier());

    assertThat(classifier.classify("적금 추천해 줘")).isEqualTo(ChatIntent.SAVINGS_RECOMMENDATION);
  }
}
