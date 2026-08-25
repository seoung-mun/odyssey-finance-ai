package com.dacon.core.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 사용자 행의 생성과 조회를 Spring Data에 위임한다. */
public interface UserAccountRepository extends JpaRepository<UserAccount, Integer> {
  /**
   * 샘플 적재 여부를 확인하는 트랜잭션 동안 사용자 행에 비관적 쓰기 잠금을 건다.
   *
   * @param id 잠글 사용자 ID
   * @return 해당 사용자가 있으면 잠긴 엔티티
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select user from UserAccount user where user.id = :id")
  Optional<UserAccount> findByIdForUpdate(@Param("id") int id);
}
