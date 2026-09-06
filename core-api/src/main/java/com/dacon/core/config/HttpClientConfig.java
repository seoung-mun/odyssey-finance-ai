package com.dacon.core.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 애플리케이션 전역에서 연결 풀을 재사용할 JDK HTTP client bean을 제공한다. */
@Configuration
public class HttpClientConfig {
  /**
   * 시스템 기본 proxy·TLS 설정을 사용하는 client를 한 번 구성한다.
   *
   * <p>connectTimeout은 각 요청의 응답 대기(timeout)와 별개로 TCP 연결 수립만 제한한다. 이걸 분리해 두면 "연결 자체가 안 됨"과 "서버가 느림"이
   * 서로 다른 예외로 구분된다.
   *
   * @return Spring 컨텍스트가 공유할 HTTP client
   */
  @Bean
  HttpClient httpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();
  }
}
