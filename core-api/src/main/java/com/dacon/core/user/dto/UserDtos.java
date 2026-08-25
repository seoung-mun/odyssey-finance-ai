package com.dacon.core.user.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 계정·온보딩 API의 요청과 응답 DTO를 모은다. */
public final class UserDtos {
  private UserDtos() {}

  /** 인적 프로필 입력이다. */
  public record ProfileInput(
      @Past(message = "생년월일은 과거여야 합니다") LocalDate birthDate,
      @Pattern(regexp = "^[0-9]{5}$", message = "지역 코드는 숫자 5자리여야 합니다") String regionCode) {}

  /** 현재 사용자와 온보딩 상태다. */
  public record MeResponse(
      int userId,
      String email,
      String displayName,
      String profileImageUrl,
      boolean onboardingComplete,
      List<String> missingSteps,
      Integer activeGoalId,
      Instant sampleDataLoadedAt) {}

  /** 인적 프로필 응답이다. */
  public record ProfileResponse(LocalDate birthDate, String regionCode, Instant updatedAt) {}

  /** 샘플 적재 결과다. */
  public record SampleResponse(boolean loaded, Instant sampleDataLoadedAt) {}
}
