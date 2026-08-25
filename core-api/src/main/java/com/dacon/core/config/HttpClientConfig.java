package com.dacon.core.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 애플리케이션 전역에서 연결 풀을 재사용할 JDK HTTP client bean을 제공한다. */
@Configuration
public class HttpClientConfig {
  /**
   * 시스템 기본 proxy·TLS·timeout 설정을 사용하는 client를 한 번 구성한다.
   *
   * @return Spring 컨텍스트가 공유할 HTTP client
   */
  @Bean
  HttpClient httpClient() {
    return HttpClient.newBuilder().build();
  }
}
