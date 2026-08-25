package com.dacon.core.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SocialAccountRepository extends JpaRepository<SocialAccount, Integer> {
  Optional<SocialAccount> findByProviderAndProviderSubject(String provider, String providerSubject);
}
