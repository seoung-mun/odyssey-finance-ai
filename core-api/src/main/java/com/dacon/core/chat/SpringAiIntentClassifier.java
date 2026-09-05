package com.dacon.core.chat;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "app.chat-ai-enabled", havingValue = "true")
public class SpringAiIntentClassifier implements IntentClassifierPort {
  private static final String PROMPT =
      """
      다음 사용자 문장의 의도를 아래 enum 중 정확히 하나로만 답하세요.
      SAVINGS_RECOMMENDATION, SAVINGS_WHAT_IF, PLAN_STATUS, SPENDING_SUMMARY,
      REPLAN_GUIDE, POLICY_SEARCH, HELP, UNKNOWN
      사용자 문장: %s
      """;

  private final ChatClient chatClient;
  private final Duration timeout;
  private final IntentClassifierPort fallback;

  @Autowired
  public SpringAiIntentClassifier(
      ChatClient.Builder builder, @Value("${app.chat-timeout:3s}") Duration timeout) {
    this(builder.build(), timeout, new DeterministicIntentClassifier());
  }

  SpringAiIntentClassifier(ChatClient chatClient, Duration timeout, IntentClassifierPort fallback) {
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("app.chat-timeout must be positive");
    }
    this.chatClient = chatClient;
    this.timeout = timeout;
    this.fallback = fallback;
  }

  @Override
  public ChatIntent classify(String message) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> future =
          executor.submit(
              () -> chatClient.prompt().user(PROMPT.formatted(message)).call().content());
      try {
        String output = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        return ChatIntent.valueOf(output == null ? "" : output.trim());
      } catch (IllegalArgumentException | ExecutionException | TimeoutException exception) {
        future.cancel(true);
        return fallback.classify(message);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return fallback.classify(message);
      }
    }
  }
}
