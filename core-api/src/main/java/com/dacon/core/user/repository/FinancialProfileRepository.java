package com.dacon.core.user.repository;

import com.dacon.core.user.entity.FinancialProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자 ID 기준 금융 프로필 저장과 조회를 Spring Data에 위임한다. */
public interface FinancialProfileRepository extends JpaRepository<FinancialProfile, Integer> {}
