package com.dacon.core.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** provider와 subject의 유일한 소셜 계정을 조회·저장한다. */
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Integer> {
  /** provider와 subject가 일치하는 계정을 반환한다. */
  Optional<SocialAccount> findByProviderAndProviderSubject(String provider, String providerSubject);

  /** 사용자 기본 표시 정보에 사용할 첫 소셜 계정을 반환한다. */
  Optional<SocialAccount> findFirstByUserIdOrderById(int userId);
}
