package com.dacon.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {
  @Test
  void problemContainsStableCodeAndRequestId() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missing");
    request.setAttribute(RequestIdFilter.ATTRIBUTE, "trace-1");

    org.springframework.http.ProblemDetail problem =
        new GlobalExceptionHandler()
            .api(new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "없습니다."), request);

    assertThat(problem.getStatus()).isEqualTo(404);
    assertThat(problem.getProperties())
        .containsEntry("code", "NOT_FOUND")
        .containsEntry("requestId", "trace-1");
  }
}
