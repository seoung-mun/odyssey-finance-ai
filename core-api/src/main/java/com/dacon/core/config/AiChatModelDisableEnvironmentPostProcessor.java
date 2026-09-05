package com.dacon.core.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** local AI kill-switch가 Ollama ChatModel auto-configuration보다 먼저 적용되게 한다. */
public class AiChatModelDisableEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  private static final String SOURCE = "odysseyExplanationAiKillSwitch";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    boolean local = environment.acceptsProfiles(Profiles.of("local"));
    boolean enabled =
        environment.getProperty("app.explanation-ai-enabled", Boolean.class, false)
            || environment.getProperty("app.chat-ai-enabled", Boolean.class, false);
    if (!local || !enabled) {
      environment
          .getPropertySources()
          .addFirst(new MapPropertySource(SOURCE, Map.of("spring.ai.model.chat", "none")));
    }
  }

  @Override
  public int getOrder() {
    // application.yml, profile-specific config, and command-line properties must
    // be available before deciding whether to suppress Spring AI auto-configuration.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
