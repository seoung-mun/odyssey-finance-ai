package com.dacon.core.explanation;

import com.dacon.core.explanation.dto.ExplanationJob;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 확정 계획에서 유래한 숫자만 모델 설명에 등장하도록 검증한다. */
public final class AllowedNumberValidator {
  private static final Pattern NUMBER = Pattern.compile("(?<![\\d])\\d[\\d,]*(?![\\d])");

  private AllowedNumberValidator() {}

  /** 설명 작업 스냅샷에서 중복 없는 허용 정수 목록을 계산한다. */
  public static Set<Long> allowedNumbers(ExplanationJob job) {
    int remainingMonths = remainingMonths(job);
    Set<Long> allowed = new LinkedHashSet<>();
    allowed.add(job.recommendedMonthlySpending());
    allowed.add(job.currentAvgVariableSpending());
    allowed.add((long) remainingMonths);
    allowed.add(job.targetAmount());
    allowed.add(job.currentSavedAmount());
    return Set.copyOf(allowed);
  }

  /** 계획 기준일과 목표일에서 설명에 쓸 잔여 개월을 계산한다. */
  public static int remainingMonths(ExplanationJob job) {
    return Math.max(
        1,
        (int)
                ChronoUnit.MONTHS.between(
                    YearMonth.from(job.asOfDate()), YearMonth.from(job.targetDate()))
            + 1);
  }

  /** 텍스트의 모든 ASCII 정수가 허용된 확정 숫자인지 검사한다. */
  public static Validation validate(String text, Set<Long> allowed) {
    List<String> failed = new ArrayList<>();
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      String token = matcher.group();
      try {
        if (!allowed.contains(Long.parseLong(token.replace(",", "")))) {
          failed.add(token.replace(",", ""));
        }
      } catch (NumberFormatException exception) {
        failed.add(token.replace(",", ""));
      }
    }
    return new Validation(failed.isEmpty(), List.copyOf(failed));
  }

  /** 숫자 검증 결과와 재교정 프롬프트에 쓸 실패 숫자 목록이다. */
  public record Validation(boolean valid, List<String> failedNumbers) {}
}
