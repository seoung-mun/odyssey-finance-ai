package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FallbackExplanationGeneratorPortTest {
  @Test
  void returnsNumberFreeFallbackWithoutModelCall() {
    ExplanationResult result =
        new FallbackExplanationGeneratorPort()
            .generate(new ExplanationRequest("확정 JSON", Set.of(800_000L), "explanation-7"));

    assertThat(result.status()).isEqualTo(ExplanationResult.Status.FALLBACK);
    assertThat(result.model()).isNull();
    assertThat(result.text()).doesNotContainPattern("\\d");
  }
}
