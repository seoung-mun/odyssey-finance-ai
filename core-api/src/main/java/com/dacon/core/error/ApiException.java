package com.dacon.core.error;

import org.springframework.http.HttpStatus;

/** 도메인·유스케이스 실패를 공개 가능한 HTTP 상태, 안정적 코드와 사용자 메시지로 운반한다. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  /**
   * 공개 가능한 실패 정보를 하나의 예외로 구성한다.
   *
   * @param status 응답에 사용할 HTTP 상태
   * @param code 클라이언트가 분기에 사용할 안정적인 오류 코드
   * @param message 사용자에게 노출 가능한 상세 메시지
   */
  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * 예외 처리기가 사용할 응답 상태를 제공한다.
   *
   * @return 응답에 사용할 HTTP 상태
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * 클라이언트가 실패 원인을 구분할 안정적 코드를 제공한다.
   *
   * @return 클라이언트 분기용 안정적인 오류 코드
   */
  public String code() {
    return code;
  }
}
