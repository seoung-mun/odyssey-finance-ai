package com.dacon.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Core API Spring Boot 프로세스를 시작한다. */
@SpringBootApplication
@EnableScheduling
public class CoreApiApplication {
  /** 전달된 실행 인자로 애플리케이션을 시작한다. */
  public static void main(String[] args) {
    SpringApplication.run(CoreApiApplication.class, args);
  }
}
