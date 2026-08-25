package com.dacon.core.config;

import com.dacon.core.auth.TokenService;
import com.dacon.core.error.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, TokenService tokens, ObjectMapper objectMapper) throws Exception {
    return http.csrf(csrf -> csrf.disable())
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

  private static void writeProblem(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String detail)
      throws IOException {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    problem.setType(URI.create("https://api.odyssey.local/problems/" + code.toLowerCase()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
