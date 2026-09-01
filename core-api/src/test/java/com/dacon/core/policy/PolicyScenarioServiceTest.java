package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import com.dacon.core.policy.PolicyDtos.ConfirmedOneTimeAward;
import com.dacon.core.policy.PolicyDtos.PolicyScenarioRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PolicyScenarioServiceTest {
  @Test
  void normalRequestDeserializes() throws Exception {
    new ObjectMapper()
        .readValue(
            """
            {"currentPlanVersionId":1,"supportGoal":"PURCHASE","answers":[],
             "confirmedAward":{"type":"ONE_TIME_FUNDING","institutionConfirmed":true,
             "amountWon":1,"startYearMonth":"2026-09"}}
            """,
            PolicyScenarioRequest.class);
  }

  @Test
  void rejectsOutOfRangeAwardBeforeReadingOrCallingAnalysis() {
    PolicyScenarioService service = new PolicyScenarioService(null, null, new ObjectMapper());
    PolicyScenarioRequest request =
        new PolicyScenarioRequest(
            1,
            "PURCHASE",
            java.util.List.of(),
            new ConfirmedOneTimeAward(
                "ONE_TIME_FUNDING", true, new ObjectMapper().valueToTree(0), "2026-09"));

    assertThatThrownBy(() -> service.compare(7, 1L, request, "scenario-red"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              org.assertj.core.api.Assertions.assertThat(exception.status())
                  .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              org.assertj.core.api.Assertions.assertThat(exception.code())
                  .isEqualTo("INVALID_POLICY_AWARD");
            });
  }
}
