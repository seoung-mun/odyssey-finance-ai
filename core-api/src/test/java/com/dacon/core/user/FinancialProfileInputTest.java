package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.user.dto.FinancialProfileInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class FinancialProfileInputTest {
  private final jakarta.validation.Validator validator =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void missingRequiredAmountIsRejected() {
    FinancialProfileInput input = new FinancialProfileInput(null, 1_000_000L);

    assertThat(validator.validate(input))
        .extracting("propertyPath")
        .asString()
        .contains("monthlyIncome");
  }
}
