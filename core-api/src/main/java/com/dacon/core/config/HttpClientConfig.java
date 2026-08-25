package com.dacon.core.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {
  @Bean
  HttpClient httpClient() {
    return HttpClient.newBuilder().build();
  }
}
