package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.entity.SpendingFloorMode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class FinancialProfileInputTest {
  private final jakarta.validation.Validator validator =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void customModeRequiresFloor() {
    FinancialProfileInput input =
        new FinancialProfileInput(3_000_000L, 1_000_000L, SpendingFloorMode.CUSTOM, null);
    assertThat(validator.validate(input))
        .extracting("message")
        .contains("CUSTOM 모드에서 소비 하한은 필수입니다");
  }

  @Test
  void offModeRejectsFloor() {
    FinancialProfileInput input =
        new FinancialProfileInput(3_000_000L, 1_000_000L, SpendingFloorMode.OFF, 500_000L);
    assertThat(validator.validate(input))
        .extracting("message")
        .contains("OFF/AUTO 모드에서 소비 하한은 비워야 합니다");
  }

  @Test
  void missingRequiredAmountIsRejected() {
    FinancialProfileInput input =
        new FinancialProfileInput(null, 1_000_000L, SpendingFloorMode.OFF, null);

    assertThat(validator.validate(input))
        .extracting("propertyPath")
        .asString()
        .contains("monthlyIncome");
  }
}
