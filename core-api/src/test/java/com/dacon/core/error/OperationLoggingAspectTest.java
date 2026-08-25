package com.dacon.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class OperationLoggingAspectTest {
  @Test
  void logsOnlyOperationRequestIdElapsedAndOutcome() throws Throwable {
    Logger logger = (Logger) LoggerFactory.getLogger(OperationLoggingAspect.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    Signature signature = mock(Signature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("UserService.me(..)");
    when(joinPoint.proceed()).thenReturn("secret-body");

    try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", "request-7")) {
      assertThat(new OperationLoggingAspect().log(joinPoint)).isEqualTo("secret-body");
    } finally {
      logger.detachAppender(appender);
    }

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages).hasSize(1);
    assertThat(messages.getFirst())
        .contains(
            "operation=UserService.me(..)", "requestId=request-7", "success=true", "error=none")
        .containsPattern("elapsed=[0-9]+")
        .doesNotContain("secret-body");
  }

  @Test
  void logsExceptionTypeWithoutMessageAndRethrows() throws Throwable {
    Logger logger = (Logger) LoggerFactory.getLogger(OperationLoggingAspect.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    Signature signature = mock(Signature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("AuthService.login(..)");
    when(joinPoint.proceed()).thenThrow(new IllegalStateException("token-value"));

    try {
      assertThatThrownBy(() -> new OperationLoggingAspect().log(joinPoint))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list.getFirst().getFormattedMessage())
        .contains("success=false", "error=IllegalStateException")
        .doesNotContain("token-value");
  }
}
