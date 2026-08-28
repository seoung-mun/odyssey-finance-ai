package com.dacon.core.auth;

import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자 행의 생성과 조회를 Spring Data에 위임한다. */
public interface UserAccountRepository extends JpaRepository<UserAccount, Integer> {}
