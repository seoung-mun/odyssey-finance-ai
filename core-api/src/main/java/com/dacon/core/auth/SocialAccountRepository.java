package com.dacon.core.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** provider와 subject의 유일한 소셜 계정을 조회·저장한다. */
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Integer> {
  /**
   * 외부 제공자 안에서 안정적인 subject로 연결 계정을 찾는다.
   *
   * @param provider 외부 인증 제공자 코드
   * @param providerSubject 제공자가 발급한 사용자 subject
   * @return 연결 계정이 있으면 해당 행
   */
  Optional<SocialAccount> findByProviderAndProviderSubject(String provider, String providerSubject);

  /**
   * 현재 사용자 응답의 표시 정보에 사용할 가장 먼저 연결된 소셜 계정을 찾는다.
   *
   * @param userId 내부 사용자 ID
   * @return 연결 계정이 있으면 ID가 가장 작은 행
   */
  Optional<SocialAccount> findFirstByUserIdOrderById(int userId);
}
