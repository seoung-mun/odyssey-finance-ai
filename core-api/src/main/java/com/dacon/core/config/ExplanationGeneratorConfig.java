package com.dacon.core.config;

import com.dacon.core.explanation.ExplanationGeneratorPort;
import com.dacon.core.explanation.FallbackExplanationGeneratorPort;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** local AI가 아닌 모든 실행 환경에 숫자 없는 설명 fallback을 제공한다. */
@Configuration
public class ExplanationGeneratorConfig {
  @Bean
  @Profile("!local")
  ExplanationGeneratorPort nonLocalFallbackExplanationGenerator() {
    return new FallbackExplanationGeneratorPort();
  }

  @Bean
  @Profile("local")
  @ConditionalOnProperty(
      name = "app.explanation-ai-enabled",
      havingValue = "false",
      matchIfMissing = true)
  ExplanationGeneratorPort disabledLocalFallbackExplanationGenerator() {
    return new FallbackExplanationGeneratorPort();
  }

  @Bean
  @Profile("local")
  @ConditionalOnProperty(name = "app.explanation-ai-enabled", havingValue = "true")
  RestClientCustomizer localOllamaTransportTimeout(
      @org.springframework.beans.factory.annotation.Value("${app.explanation-timeout}")
          Duration timeout) {
    Duration transportTimeout =
        timeout.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : timeout;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(transportTimeout).build());
    factory.setReadTimeout(transportTimeout);
    return builder -> builder.requestFactory(factory);
  }
}
