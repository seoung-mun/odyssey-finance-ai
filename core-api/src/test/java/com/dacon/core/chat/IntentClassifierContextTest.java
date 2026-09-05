package com.dacon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.config.IntentClassifierConfig;
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

class IntentClassifierContextTest {
  @Test
  void nonLocalUsesOneFallbackAndNoChatModel() {
    try (ConfigurableApplicationContext context = context(false, false)) {
      assertThat(context.getBeansOfType(IntentClassifierPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) -> assertThat(port).isInstanceOf(DeterministicIntentClassifier.class));
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
  }

  @Test
  void disabledLocalUsesOneFallbackAndNoChatModel() {
    try (ConfigurableApplicationContext context = context(true, false)) {
      assertThat(context.getBeansOfType(IntentClassifierPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) -> assertThat(port).isInstanceOf(DeterministicIntentClassifier.class));
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
  }

  @Test
  void enabledLocalUsesOneSpringClassifierAndOneChatModel() {
    try (ConfigurableApplicationContext context = context(true, true)) {
      assertThat(context.getBeansOfType(IntentClassifierPort.class))
          .hasSize(1)
          .allSatisfy(
              (name, port) -> assertThat(port).isInstanceOf(SpringAiIntentClassifier.class));
      assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
    }
  }

  private ConfigurableApplicationContext context(boolean local, boolean enabled) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(TruthTableApplication.class)
            .web(WebApplicationType.NONE)
            .properties("app.chat-timeout=3s", "spring.main.banner-mode=off");
    if (local) {
      builder.profiles("local");
    }
    return builder.run("--app.explanation-ai-enabled=false", "--app.chat-ai-enabled=" + enabled);
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
  @Import({IntentClassifierConfig.class, SpringAiIntentClassifier.class})
  static class TruthTableApplication {}
}
