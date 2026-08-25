package com.dacon.core.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 사용자 행의 생성과 조회를 Spring Data에 위임한다. */
public interface UserAccountRepository extends JpaRepository<UserAccount, Integer> {
  /** 샘플 적재 경합을 막기 위해 사용자 행을 잠근다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select user from UserAccount user where user.id = :id")
  Optional<UserAccount> findByIdForUpdate(@Param("id") int id);
}
