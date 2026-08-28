package com.dacon.core.user;

import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.dto.UserDtos.MeResponse;
import com.dacon.core.user.dto.UserDtos.ProfileInput;
import com.dacon.core.user.dto.UserDtos.ProfileResponse;

/** 현재 계정의 온보딩 상태와 인적·금융 프로필 유스케이스를 정의한다. */
public interface UserService {
  /**
   * 사용자 표시 정보와 온보딩 누락 단계를 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @return 소셜 표시 정보, 누락 단계와 ACTIVE 목표
   * @throws com.dacon.core.error.ApiException 사용자 또는 연결 소셜 계정이 없는 경우
   */
  MeResponse me(int userId);

  /**
   * 사용자의 인적 프로필을 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @return 저장된 생년월일·지역
   * @throws com.dacon.core.error.ApiException 프로필이 없는 경우
   */
  ProfileResponse profile(int userId);

  /**
   * 사용자의 인적 프로필을 생성하거나 현재 값을 덮어쓴다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param input 검증을 마친 인적 프로필
   * @return 저장된 프로필
   * @throws com.dacon.core.error.ApiException 사용자가 없는 경우
   */
  ProfileResponse upsertProfile(int userId, ProfileInput input);

  /**
   * 사용자의 현재 금융 프로필을 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @return 월소득·고정비
   * @throws com.dacon.core.error.ApiException 금융 프로필이 없는 경우
   */
  FinancialProfileResponse financialProfile(int userId);

  /**
   * 사용자의 금융 프로필을 생성하거나, ACTIVE 목표가 없을 때 현재 값을 변경한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param input 검증을 마친 금융 프로필
   * @return 저장된 값과 현재 구현의 재계획 미발생 결과
   * @throws com.dacon.core.error.ApiException 사용자가 없거나 ACTIVE 목표의 금융 값을 변경하려는 경우
   */
  FinancialProfileResponse upsertFinancialProfile(int userId, FinancialProfileInput input);
}
