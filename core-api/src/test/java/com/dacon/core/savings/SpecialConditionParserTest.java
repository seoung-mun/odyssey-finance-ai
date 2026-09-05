package com.dacon.core.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SpecialConditionParserTest {
  private final SpecialConditionParser parser = new SpecialConditionParser();

  @Test
  void extractsPercentPointRatesWithoutInventingConditions() {
    var result = parser.parse("급여이체 시 연 0.7%p. 첫 거래 고객 0.3%p\n단순 안내 문구");

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(SavingsCatalogSnapshot.Condition::bonusRate)
        .containsExactly(new BigDecimal("0.7"), new BigDecimal("0.3"));
  }

  @Test
  void returnsEmptyForMissingSourceText() {
    assertThat(parser.parse(null)).isEmpty();
    assertThat(parser.parse("  ")).isEmpty();
  }
}
