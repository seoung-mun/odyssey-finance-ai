package com.dacon.core.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from RefreshSession session where session.tokenDigest = :digest")
  Optional<RefreshSession> findByDigestForUpdate(@Param("digest") String digest);
}
