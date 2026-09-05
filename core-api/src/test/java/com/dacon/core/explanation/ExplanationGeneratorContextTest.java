package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.config.ExplanationGeneratorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

class ExplanationGeneratorContextTest {
  @Test
  void nonLocalContextHasOnlyFallbackAndNoChatModel() {
    try (ConfigurableApplicationContext context = context(false, false)) {
      assertThat(context.getBeansOfType(ExplanationGeneratorPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) ->
                  assertThat(port).isInstanceOf(FallbackExplanationGeneratorPort.class));
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
  }

  @Test
  void e2eContextHasOnlyFallbackAndNoChatModel() {
    try (ConfigurableApplicationContext context = context(false, false, "e2e")) {
      assertThat(context.getBeansOfType(ExplanationGeneratorPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) ->
                  assertThat(port).isInstanceOf(FallbackExplanationGeneratorPort.class));
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
  }

  @Test
  void disabledLocalContextHasOnlyFallbackAndNoChatModel() {
    try (ConfigurableApplicationContext context = context(true, false)) {
      assertThat(context.getBeansOfType(ExplanationGeneratorPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) ->
                  assertThat(port).isInstanceOf(FallbackExplanationGeneratorPort.class));
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
  }

  @Test
  void enabledLocalContextHasOnlySpringAiGenerator() {
    try (ConfigurableApplicationContext context = context(true, true)) {
      assertThat(context.getBeansOfType(ExplanationGeneratorPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) -> assertThat(port).isInstanceOf(SpringAiExplanationClient.class));
      assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
    }
  }

  private ConfigurableApplicationContext context(boolean local, boolean enabled) {
    return context(local, enabled, new String[0]);
  }

  private ConfigurableApplicationContext context(
      boolean local, boolean enabled, String... profiles) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(TruthTableApplication.class)
            .web(WebApplicationType.NONE)
            .properties("app.explanation-timeout=15s", "spring.main.banner-mode=off");
    if (local) {
      builder.profiles("local");
    }
    if (profiles.length > 0) {
      builder.profiles(profiles);
    }
    return builder.run("--app.explanation-ai-enabled=" + enabled);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        SecurityAutoConfiguration.class
      })
  @Import({ExplanationGeneratorConfig.class, SpringAiExplanationClient.class})
  static class TruthTableApplication {}
}
