package com.dacon.core.config;

import com.dacon.core.chat.DeterministicIntentClassifier;
import com.dacon.core.chat.IntentClassifierPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class IntentClassifierConfig {
  @Bean
  @Profile("!local")
  IntentClassifierPort nonLocalIntentClassifier() {
    return new DeterministicIntentClassifier();
  }

  @Bean
  @Profile("local")
  @ConditionalOnProperty(name = "app.chat-ai-enabled", havingValue = "false", matchIfMissing = true)
  IntentClassifierPort disabledLocalIntentClassifier() {
    return new DeterministicIntentClassifier();
  }
}
