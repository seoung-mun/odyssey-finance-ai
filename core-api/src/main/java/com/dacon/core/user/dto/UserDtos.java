package com.dacon.core.user.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 계정·인적 프로필·샘플 적재 HTTP 계약의 불변 타입을 모은다. */
public final class UserDtos {
  private UserDtos() {}

  /**
   * 인적 프로필 입력이다.
   *
   * @param birthDate 과거 날짜인 선택적 생년월일
   * @param regionCode 행정표준코드 시군구 5자리, 선택하지 않으면 {@code null}
   */
  public record ProfileInput(
      @Past(message = "생년월일은 과거여야 합니다") LocalDate birthDate,
      @Pattern(regexp = "^[0-9]{5}$", message = "지역 코드는 숫자 5자리여야 합니다") String regionCode) {}

  /**
   * 현재 사용자와 온보딩 상태다.
   *
   * @param userId 내부 사용자 ID
   * @param email 연결 소셜 계정의 검증된 이메일
   * @param displayName 선택적 표시 이름
   * @param profileImageUrl 선택적 프로필 이미지 URL
   * @param onboardingComplete 필수 단계가 모두 존재하면 {@code true}
   * @param missingSteps PROFILE·FINANCIAL_PROFILE·GOAL 중 누락된 단계
   * @param activeGoalId ACTIVE 목표 ID, 없으면 {@code null}
   * @param sampleDataLoadedAt 샘플 최초 적재 시각, 적재하지 않았으면 {@code null}
   */
  public record MeResponse(
      int userId,
      String email,
      String displayName,
      String profileImageUrl,
      boolean onboardingComplete,
      List<String> missingSteps,
      Integer activeGoalId,
      Instant sampleDataLoadedAt) {}

  /**
   * 인적 프로필 응답이다.
   *
   * @param birthDate 선택적 생년월일
   * @param regionCode 선택적 5자리 시군구 코드
   * @param updatedAt 마지막 저장 시각
   */
  public record ProfileResponse(LocalDate birthDate, String regionCode, Instant updatedAt) {}

  /**
   * 샘플 적재 결과다.
   *
   * @param loaded 이번 호출이 샘플을 새로 적재했으면 {@code true}
   * @param sampleDataLoadedAt 최초 샘플 적재 완료 시각
   */
  public record SampleResponse(boolean loaded, Instant sampleDataLoadedAt) {}
}
