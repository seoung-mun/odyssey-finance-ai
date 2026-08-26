package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import com.dacon.core.error.RequestIdFilter;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.ReplanDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class ReplanControllerTest {
  @Test
  void manualInfeasibleReplanReturnsPlanInfeasibleInsteadOfSuccess() {
    ReplanService service = mock(ReplanService.class);
    Jwt jwt = mock(Jwt.class);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(RequestIdFilter.ATTRIBUTE, "req-1");
    when(jwt.getSubject()).thenReturn("7");
    when(service.request(7, 9, "req-1"))
        .thenReturn(
            new ReplanService.ReplanRequestResult(
                12, new PlanCreation(true, 13, null, "가용 유동지출 예산이 부족합니다.", 10L)));

    assertThatThrownBy(
            () -> new ReplanController(service, new ObjectMapper()).request(jwt, 9, request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            exception -> {
              ApiException api = (ApiException) exception;
              assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(api.code()).isEqualTo("PLAN_INFEASIBLE");
            });
  }

  @Test
  void nullReplanDecisionIsValidationFailure() {
    assertThat(
            Validation.buildDefaultValidatorFactory()
                .getValidator()
                .validate(new ReplanDecision(null)))
        .isNotEmpty();
  }

  @Test
  void exposesAllReplanOperations() throws Exception {
    assertThat(
            ReplanController.class
                .getDeclaredMethod(
                    "events", org.springframework.security.oauth2.jwt.Jwt.class, int.class)
                .getAnnotation(GetMapping.class)
                .value())
        .containsExactly("/goals/{goalId}/replan-events");
    assertThat(
            ReplanController.class
                .getDeclaredMethod(
                    "request",
                    org.springframework.security.oauth2.jwt.Jwt.class,
                    int.class,
                    jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/goals/{goalId}/replan");
    assertThat(
            ReplanController.class
                .getDeclaredMethod(
                    "decision",
                    org.springframework.security.oauth2.jwt.Jwt.class,
                    int.class,
                    com.dacon.core.plan.dto.PlanningDtos.ReplanDecision.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/replan-events/{replanEventId}/decision");
    assertThat(
            ReplanController.class
                .getDeclaredMethod(
                    "retry",
                    org.springframework.security.oauth2.jwt.Jwt.class,
                    int.class,
                    jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/replan-events/{replanEventId}/retry");
  }
}
