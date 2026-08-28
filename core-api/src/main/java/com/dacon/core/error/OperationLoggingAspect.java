package com.dacon.core.error;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** HTTP·서비스 공개 연산의 이름, request ID, 지연시간과 성공 여부만 남기고 금융 입력·응답은 기록하지 않는다. */
@Aspect
@Component
public class OperationLoggingAspect {
  private static final Logger log = LoggerFactory.getLogger(OperationLoggingAspect.class);

  /**
   * 대상 연산을 정확히 한 번 실행하고 성공·실패 로그를 남긴 뒤 결과 또는 원래 예외를 보존한다.
   *
   * @param joinPoint 실행할 controller 또는 service 호출
   * @return 대상 호출의 원래 반환값
   * @throws Throwable 대상 호출이 던진 원래 예외
   */
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

  /**
   * 민감한 인자 없이 완료된 연산의 경과시간과 결과 메타데이터를 INFO로 기록한다.
   *
   * @param joinPoint 완료된 호출
   * @param started 호출 시작 시점의 단조 증가 나노초
   * @param success 정상 반환 여부
   * @param error 실패 예외의 단순 클래스명, 성공이면 {@code none}
   */
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
