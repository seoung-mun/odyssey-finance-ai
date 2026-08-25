package com.dacon.core.financial;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class FinancialProfileInputTest {
  private final jakarta.validation.Validator validator =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void customModeRequiresFloor() {
    var input = new FinancialProfileInput(3_000_000, 1_000_000, SpendingFloorMode.CUSTOM, null);
    assertThat(validator.validate(input))
        .extracting("message")
        .contains("CUSTOM 모드에서 소비 하한은 필수입니다");
  }

  @Test
  void offModeRejectsFloor() {
    var input = new FinancialProfileInput(3_000_000, 1_000_000, SpendingFloorMode.OFF, 500_000L);
    assertThat(validator.validate(input))
        .extracting("message")
        .contains("OFF/AUTO 모드에서 소비 하한은 비워야 합니다");
  }
}
