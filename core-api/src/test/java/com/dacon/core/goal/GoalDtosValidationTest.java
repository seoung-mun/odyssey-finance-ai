package com.dacon.core.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class GoalDtosValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void goalCreationRequiresTargetDate() {
    assertThat(validator.validate(new GoalRequest("비상금", 1_000_000L, 0L, null)))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("targetDate");
  }

  @Test
  void scheduledExpenseCreationRequiresScheduledDate() {
    assertThat(validator.validate(new ScheduledExpenseInput("이사비", 300_000L, null)))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("scheduledDate");
  }

  @Test
  void patchDatesRemainOptional() {
    assertThat(
            validator.validate(
                new com.dacon.core.goal.dto.GoalDtos.GoalPatch(null, null, null, null, null)))
        .isEmpty();
    assertThat(
            validator.validate(
                new com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch(null, null, null, null)))
        .isEmpty();
  }
}
