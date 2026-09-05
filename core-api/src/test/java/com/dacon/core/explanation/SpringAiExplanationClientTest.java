package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SpringAiExplanationClientTest {
  private final ChatClient chatClient = mock(ChatClient.class);
  private final ChatClient.ChatClientRequestSpec request =
      mock(ChatClient.ChatClientRequestSpec.class);
  private final ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

  @Test
  void validTextIsReadyWithoutCorrection() {
    clientReturns("월 800,000원을 사용하세요.");
    SpringAiExplanationClient client = client(Duration.ofSeconds(1));

    ExplanationResult result = client.generate(request());

    assertThat(result.status()).isEqualTo(ExplanationResult.Status.READY);
    assertThat(result.retryCount()).isZero();
    verify(response).content();
  }

  @Test
  void invalidNumberIsCorrectedAtMostTwice() {
    clientReturns("월 999원을 사용하세요.", "월 800,000원을 사용하세요.");
    SpringAiExplanationClient client = client(Duration.ofSeconds(1));

    ExplanationResult result = client.generate(request());

    assertThat(result.status()).isEqualTo(ExplanationResult.Status.READY);
    assertThat(result.retryCount()).isEqualTo(1);
    verify(response, times(2)).content();
  }

  @Test
  void wallClockDeadlineReturnsFallback() {
    clientReturnsAfter(Duration.ofMillis(200), "월 800,000원을 사용하세요.");
    SpringAiExplanationClient client = client(Duration.ofMillis(30));
    Instant started = Instant.now();

    ExplanationResult result = client.generate(request());

    assertThat(result.status()).isEqualTo(ExplanationResult.Status.FALLBACK);
    assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofMillis(150));
  }

  private SpringAiExplanationClient client(Duration timeout) {
    return new SpringAiExplanationClient(
        chatClient,
        "qwen3.5:4b-q4_K_M",
        timeout,
        Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC));
  }

  private ExplanationRequest request() {
    return new ExplanationRequest("확정 JSON", Set.of(800_000L), "explanation-7");
  }

  private void clientReturns(String... texts) {
    when(chatClient.prompt()).thenReturn(request);
    when(request.user(anyString())).thenReturn(request);
    when(request.call()).thenReturn(response);
    when(response.content())
        .thenReturn(texts[0], java.util.Arrays.copyOfRange(texts, 1, texts.length));
  }

  private void clientReturnsAfter(Duration delay, String text) {
    when(chatClient.prompt()).thenReturn(request);
    when(request.user(anyString())).thenReturn(request);
    when(request.call()).thenReturn(response);
    when(response.content())
        .thenAnswer(
            ignored -> {
              Thread.sleep(delay.toMillis());
              return text;
            });
  }
}
