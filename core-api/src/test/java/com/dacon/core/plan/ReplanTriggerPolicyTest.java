package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReplanTriggerPolicyTest {
  @Test
  void shockTriggersAtP95TimesOnePointFifteen() {
    var samples = LongStream.rangeClosed(1, 100).boxed().toList();
    assertThat(ReplanTriggerPolicy.shock(samples, 108)).isFalse();
    assertThat(ReplanTriggerPolicy.shock(samples, 109)).isFalse();
    assertThat(ReplanTriggerPolicy.shock(samples, 110)).isTrue();
    assertThat(ReplanTriggerPolicy.shockThreshold(95)).isEqualTo(110);
  }

  @Test
  void driftOnlyTriggersAtCheckpointsAtOrAboveOnePointTwo() {
    LocalDate seventh = LocalDate.of(2026, 8, 7);
    assertThat(ReplanTriggerPolicy.drift(seventh.minusDays(1), 1_000, 3_100)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(seventh, 1_000, 270)).isFalse();
    assertThat(ReplanTriggerPolicy.drift(seventh, 1_000, 271)).isTrue();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 14), 1_000, 542)).isTrue();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 21), 1_000, 813)).isTrue();
  }

  @Test
  void comparisonsAcceptInt64MaximumWithoutOverflow() {
    assertThat(ReplanTriggerPolicy.shock(java.util.List.of(Long.MAX_VALUE), Long.MAX_VALUE))
        .isFalse();
    assertThat(ReplanTriggerPolicy.drift(LocalDate.of(2026, 8, 7), Long.MAX_VALUE, Long.MAX_VALUE))
        .isTrue();
  }

  @Test
  void plannedCumulativeAcceptsInt64MaximumWithoutOverflow() {
    assertThat(ReplanTriggerPolicy.plannedCumulative(Long.MAX_VALUE, LocalDate.of(2026, 8, 21)))
        .isEqualTo(6_248_090_734_643_557_804L);
  }
}
