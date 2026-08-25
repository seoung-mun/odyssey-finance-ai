package com.dacon.core.error;

import org.springframework.http.HttpStatus;

/** 공개할 HTTP 상태와 안정적인 오류 코드를 운반한다. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  /** 상태, 코드, 사용자용 메시지로 API 예외를 만든다. */
  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /** 응답 HTTP 상태를 반환한다. */
  public HttpStatus status() {
    return status;
  }

  /** 클라이언트 분기용 오류 코드를 반환한다. */
  public String code() {
    return code;
  }
}
