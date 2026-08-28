package com.dacon.core.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 허용 형식의 요청 추적 ID를 재사용하거나 새로 생성해 request attribute, MDC와 응답 헤더에 전파한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  /** 클라이언트 요청 ID를 받고 모든 응답에 돌려주는 HTTP 헤더 이름이다. */
  public static final String HEADER = "X-Request-ID";

  /** 보안 필터와 오류 처리기가 같은 요청 ID를 읽는 servlet request attribute 키다. */
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

  /**
   * 최대 64자의 안전한 요청 ID만 신뢰하고, 그 외 값은 UUID로 교체한 뒤 필터 체인을 실행한다.
   *
   * @param request 현재 HTTP 요청
   * @param response 추적 헤더를 기록할 HTTP 응답
   * @param chain 이어서 실행할 필터 체인
   * @throws ServletException 하위 필터 또는 서블릿 처리 실패
   * @throws IOException 요청·응답 입출력 실패
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String requestId =
        supplied != null && supplied.matches("^[A-Za-z0-9._-]{1,64}$")
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader(HEADER, requestId);
    try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
      chain.doFilter(request, response);
    }
  }
}
