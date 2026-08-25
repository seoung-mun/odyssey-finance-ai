package com.dacon.core.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** controller 예외를 RFC 7807 형식으로 통일하고 예상 밖 오류를 기록한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 도메인 API 예외의 상태와 코드를 그대로 안전하게 반환한다. */
  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException exception, HttpServletRequest request) {
    return problem(exception.status(), exception.code(), exception.getMessage(), request);
  }

  /** Bean Validation 오류를 필드 오류가 포함된 400으로 반환한다. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.", request);
    List<FieldError> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** 역직렬화와 제약 위반 요청을 일반화한 400으로 반환한다. */
  @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
  ProblemDetail invalidRequest(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.", request);
  }

  /** 등록되지 않은 경로를 404로 반환한다. */
  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail notFound(NoResourceFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다.", request);
  }

  /** DB 무결성 경합을 최신 상태 재조회가 필요한 409로 반환한다. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "STATE_CONFLICT", "최신 상태를 다시 조회해 주세요.", request);
  }

  /** 예상 밖 오류는 request ID와 stack trace를 기록하고 일반화한 500을 반환한다. */
  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
    log.error(
        "예상하지 못한 요청 처리 오류 requestId={}",
        request.getAttribute(RequestIdFilter.ATTRIBUTE),
        exception);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "잠시 후 다시 시도해 주세요.", request);
  }

  /** 공통 RFC 7807 속성이 포함된 오류 본문을 만든다. */
  static ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setType(URI.create("https://api.odyssey.local/problems/" + code.toLowerCase()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    return problem;
  }

  /** 입력 필드 이름과 검증 메시지를 운반한다. */
  record FieldError(String field, String message) {}
}
