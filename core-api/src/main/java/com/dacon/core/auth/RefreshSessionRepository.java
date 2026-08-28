package com.dacon.core.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** refresh session을 저장하고 회전 중 digest 행을 잠근다. */
interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
  /**
   * digest가 같은 세션을 비관적 쓰기 잠금과 함께 조회해 동시 refresh 재사용을 직렬화한다.
   *
   * @param digest refresh 토큰 원문의 SHA-256 digest
   * @return 일치하는 세션이 있으면 잠긴 행
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from RefreshSession session where session.tokenDigest = :digest")
  Optional<RefreshSession> findByDigestForUpdate(@Param("digest") String digest);
}
