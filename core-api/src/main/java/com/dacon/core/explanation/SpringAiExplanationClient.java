package com.dacon.core.explanation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/** 단일 전체 deadline 안에서 로컬 Ollama 설명을 생성하고 숫자 오류를 최대 두 번 교정한다. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.explanation-ai-enabled", havingValue = "true")
public class SpringAiExplanationClient implements ExplanationGeneratorPort {
  private static final int MAX_CORRECTIONS = 2;
  private final ChatClient chatClient;
  private final String model;
  private final Duration timeout;
  private final Clock clock;

  @Autowired
  public SpringAiExplanationClient(
      ChatClient.Builder chatClientBuilder,
      @Value("${spring.ai.ollama.chat.options.model}") String model,
      @Value("${app.explanation-timeout}") Duration timeout) {
    this(chatClientBuilder.build(), model, timeout, Clock.systemUTC());
  }

  SpringAiExplanationClient(ChatClient chatClient, String model, Duration timeout, Clock clock) {
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("app.explanation-timeout must be positive");
    }
    this.chatClient = chatClient;
    this.model = model;
    this.timeout = timeout;
    this.clock = clock;
  }

  @Override
  public ExplanationResult generate(ExplanationRequest request) {
    Instant deadline = clock.instant().plus(timeout);
    String prompt = request.prompt();
    for (int attempt = 0; attempt <= MAX_CORRECTIONS; attempt++) {
      String text = callBeforeDeadline(prompt, deadline);
      if (text == null || text.isBlank()) {
        return ExplanationResult.fallback();
      }
      AllowedNumberValidator.Validation validation =
          AllowedNumberValidator.validate(text, request.allowedNumbers());
      if (validation.valid()) {
        return new ExplanationResult(
            ExplanationResult.Status.READY, text, model, attempt, List.of(), clock.instant());
      }
      if (attempt == MAX_CORRECTIONS || !clock.instant().isBefore(deadline)) {
        return ExplanationResult.fallback();
      }
      prompt = correctionPrompt(request.prompt(), validation.failedNumbers());
    }
    return ExplanationResult.fallback();
  }

  private String callBeforeDeadline(String prompt, Instant deadline) {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isNegative() || remaining.isZero()) {
      return null;
    }
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> call =
          executor.submit(() -> chatClient.prompt().user(prompt).call().content());
      try {
        return call.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return null;
      } catch (ExecutionException | TimeoutException exception) {
        call.cancel(true);
        return null;
      }
    }
  }

  private String correctionPrompt(String originalPrompt, List<String> failedNumbers) {
    return originalPrompt
        + " 이전 응답에 허용되지 않은 숫자가 포함됐습니다: "
        + String.join(", ", failedNumbers)
        + ". 허용 숫자만 사용해 전체 문장을 다시 작성하세요.";
  }
}
