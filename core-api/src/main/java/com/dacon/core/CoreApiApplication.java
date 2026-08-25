package com.dacon.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 인증된 금융 데이터 API와 백그라운드 스케줄러를 구성하는 Spring Boot 진입점이다. */
@SpringBootApplication
@EnableScheduling
public class CoreApiApplication {
  /**
   * Spring 컨텍스트를 구성하고 내장 웹 서버를 시작한다.
   *
   * @param args Spring Boot에 전달할 명령행 인자
   */
  public static void main(String[] args) {
    SpringApplication.run(CoreApiApplication.class, args);
  }
}
