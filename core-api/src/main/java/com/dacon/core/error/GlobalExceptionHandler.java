package com.dacon.core.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
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

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException exception, HttpServletRequest request) {
    return problem(exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
    var problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.", request);
    var errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
  ProblemDetail invalidRequest(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.", request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail notFound(NoResourceFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다.", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "STATE_CONFLICT", "최신 상태를 다시 조회해 주세요.", request);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
    log.error(
        "예상하지 못한 요청 처리 오류 requestId={}",
        request.getAttribute(RequestIdFilter.ATTRIBUTE),
        exception);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "잠시 후 다시 시도해 주세요.", request);
  }

  static ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setType(URI.create("https://api.odyssey.local/problems/" + code.toLowerCase()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    return problem;
  }

  record FieldError(String field, String message) {}
}
