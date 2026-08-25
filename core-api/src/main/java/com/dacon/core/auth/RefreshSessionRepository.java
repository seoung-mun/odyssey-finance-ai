package com.dacon.core.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** refresh session을 저장하고 회전 중 digest 행을 잠근다. */
interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
  /** digest가 같은 session을 쓰기 잠금과 함께 반환한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from RefreshSession session where session.tokenDigest = :digest")
  Optional<RefreshSession> findByDigestForUpdate(@Param("digest") String digest);
}
