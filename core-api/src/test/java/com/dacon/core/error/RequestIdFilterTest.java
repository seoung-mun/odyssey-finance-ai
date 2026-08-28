package com.dacon.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void preservesIncomingRequestIdOnRequestAndResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "trace-123");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) ->
            assertThat(req.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("trace-123");

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("trace-123");
  }

  @Test
  void generatesRequestIdWhenHeaderIsBlank() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
  }

  @Test
  void replacesUnsafeRequestId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "bad id\nvalue");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).doesNotContain("bad id");
  }

  @Test
  void replacesRequestIdLongerThanFastApiLimit() throws Exception {
    String supplied = "a".repeat(65);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, supplied);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(supplied).hasSize(36);
  }
}
