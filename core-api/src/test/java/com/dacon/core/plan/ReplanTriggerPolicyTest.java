package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReplanTriggerPolicyTest {
  @Test
  void shockUsesGreaterOfP95AndFifteenPercentOfSelectedBudget() {
    var samples = LongStream.rangeClosed(1, 100).boxed().toList();
    assertThat(ReplanTriggerPolicy.shock(samples, 1_000, 149)).isFalse();
    assertThat(ReplanTriggerPolicy.shock(samples, 1_000, 150)).isTrue();
    assertThat(ReplanTriggerPolicy.shock(samples, 500, 94)).isFalse();
    assertThat(ReplanTriggerPolicy.shock(samples, 500, 95)).isTrue();
    assertThat(ReplanTriggerPolicy.shockThreshold(95, 1_000)).isEqualTo(150);
  }

  @Test
  void driftOnlyTriggersAboveFixedCheckpointThresholds() {
    LocalDate seventh = LocalDate.of(2026, 8, 7);
    assertThat(ReplanTriggerPolicy.drift(seventh.minusDays(1), 1_000, 3_100)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(seventh, 1_000, 300)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(seventh, 1_000, 301)).isTrue();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 14), 1_000, 600)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 14), 1_000, 601)).isTrue();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 21), 1_000, 900)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 21), 1_000, 901)).isTrue();
  }

  @Test
  void comparisonsAcceptInt64MaximumWithoutOverflow() {
    assertThat(
            ReplanTriggerPolicy.shock(
                java.util.List.of(Long.MAX_VALUE), Long.MAX_VALUE, Long.MAX_VALUE))
        .isTrue();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 7), Long.MAX_VALUE, Long.MAX_VALUE))
        .isTrue();
  }

  @Test
  void plannedCumulativeAcceptsInt64MaximumWithoutOverflow() {
    assertThat(ReplanTriggerPolicy.plannedCumulative(Long.MAX_VALUE, LocalDate.of(2026, 8, 21)))
        .isEqualTo(6_917_529_027_641_081_855L);
  }
}
