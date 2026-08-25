package com.dacon.core.error;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** HTTP·서비스 연산의 식별자, 지연시간과 성공 여부만 기록한다. */
@Aspect
@Component
public class OperationLoggingAspect {
  private static final Logger log = LoggerFactory.getLogger(OperationLoggingAspect.class);

  /** 민감한 인자와 반환값을 제외하고 연산 결과를 기록한다. */
  @Around(
      "execution(public * com.dacon.core..*Controller.*(..))"
          + " || execution(public * com.dacon.core..*Service*.*(..))")
  Object log(ProceedingJoinPoint joinPoint) throws Throwable {
    long started = System.nanoTime();
    try {
      Object result = joinPoint.proceed();
      write(joinPoint, started, true, "none");
      return result;
    } catch (Throwable throwable) {
      write(joinPoint, started, false, throwable.getClass().getSimpleName());
      throw throwable;
    }
  }

  private void write(ProceedingJoinPoint joinPoint, long started, boolean success, String error) {
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    String requestId = MDC.get("requestId");
    log.info(
        "operation={} requestId={} elapsed={} success={} error={}",
        joinPoint.getSignature().toShortString(),
        requestId == null ? "none" : requestId,
        elapsed,
        success,
        error);
  }
}
