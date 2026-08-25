package com.dacon.core.user;

import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.dto.UserDtos.MeResponse;
import com.dacon.core.user.dto.UserDtos.ProfileInput;
import com.dacon.core.user.dto.UserDtos.ProfileResponse;
import com.dacon.core.user.dto.UserDtos.SampleResponse;

/** 계정·온보딩 유스케이스 계약이다. */
public interface UserService {
  MeResponse me(int userId);

  ProfileResponse profile(int userId);

  ProfileResponse upsertProfile(int userId, ProfileInput input);

  SampleResponse loadSample(int userId);

  FinancialProfileResponse financialProfile(int userId);

  FinancialProfileResponse upsertFinancialProfile(int userId, FinancialProfileInput input);
}
