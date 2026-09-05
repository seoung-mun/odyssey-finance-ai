package com.dacon.core.config;

import com.dacon.core.auth.TokenService;
import com.dacon.core.error.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** 세션을 만들지 않는 bearer 인증, 공개 경로와 RFC 7807 보안 오류 응답을 설정한다. */
@Configuration
public class SecurityConfig {
  /**
   * 인증·health 경로만 공개하고 나머지 요청에는 자체 access JWT 검증을 요구한다.
   *
   * @param http Spring Security 설정 빌더
   * @param tokens access 전용 JWT decoder 제공자
   * @param objectMapper 보안 필터 단계 오류 본문 직렬화기
   * @return stateless 보안 필터 체인
   * @throws Exception Spring Security 구성에 실패한 경우
   */
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, TokenService tokens, ObjectMapper objectMapper) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/api/v1/auth/**", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.decoder(tokens.accessDecoder()))
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(
                                objectMapper,
                                request,
                                response,
                                401,
                                "AUTHENTICATION_REQUIRED",
                                "로그인이 필요합니다."))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(
                                objectMapper,
                                request,
                                response,
                                403,
                                "ACCESS_DENIED",
                                "접근 권한이 없습니다.")))
        .build();
  }

  /** 운영 Web origin 하나만 credentialed CORS 대상으로 허용한다. 빈 값이면 교차 출처를 막는다. */
  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${app.cors-allowed-origin:}") String allowedOrigin) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of());
    if (!allowedOrigin.isBlank()) {
      configuration.setAllowedOrigins(List.of(allowedOrigin));
    }
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "X-Request-Id", "X-E2E-Token"));
    configuration.setExposedHeaders(List.of("X-Request-Id"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  /**
   * MVC 예외 처리기보다 앞선 보안 실패를 request ID가 포함된 RFC 7807 JSON으로 기록한다.
   *
   * @param mapper 오류 본문 직렬화기
   * @param request 실패한 HTTP 요청
   * @param response 오류를 기록할 HTTP 응답
   * @param status 응답 HTTP 상태 코드
   * @param code 클라이언트 분기용 안정적 오류 코드
   * @param detail 사용자에게 노출할 상세 메시지
   * @throws IOException 오류 본문을 응답 스트림에 쓸 수 없는 경우
   */
  private static void writeProblem(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String detail)
      throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    problem.setType(URI.create("https://api.odyssey.local/problems/" + code.toLowerCase()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
