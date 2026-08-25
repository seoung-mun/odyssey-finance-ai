package com.dacon.core.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 내부 HTTP 호출에 사용하는 JDK client bean을 제공한다. */
@Configuration
public class HttpClientConfig {
  /** 시스템 기본 설정의 재사용 가능한 HTTP client를 반환한다. */
  @Bean
  HttpClient httpClient() {
    return HttpClient.newBuilder().build();
  }
}
