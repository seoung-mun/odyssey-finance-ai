package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

class AuthServiceTest {
  @Test
  void springSelectsProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(UserAccountRepository.class, () -> mock(UserAccountRepository.class));
      context.registerBean(
          SocialAccountRepository.class, () -> mock(SocialAccountRepository.class));
      context.registerBean(
          RefreshSessionRepository.class, () -> mock(RefreshSessionRepository.class));
      context.registerBean(TokenService.class, () -> mock(TokenService.class));
      context.registerBean(
          PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
      context.register(AuthServiceImpl.class);

      context.refresh();

      assertThat(context.getBean(AuthServiceImpl.class)).isNotNull();
    }
  }

  @Test
  void concurrentFirstLoginRequeriesExistingAccount() {
    UserAccountRepository users = mock(UserAccountRepository.class);
    SocialAccountRepository socials = mock(SocialAccountRepository.class);
    RefreshSessionRepository sessions = mock(RefreshSessionRepository.class);
    TokenService tokens =
        new TokenService("01234567890123456789012345678901", java.time.Clock.systemUTC());
    TransactionOperations transactions = mock(TransactionOperations.class);
    when(transactions.execute(any()))
        .thenAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallback<?> action =
                  invocation.getArgument(0);
              return action.doInTransaction(null);
            });
    SocialAccount existing = new SocialAccount(8, new GoogleIdentity("sub", "a@b.c", null, null));
    when(socials.findByProviderAndProviderSubject("GOOGLE", "sub"))
        .thenThrow(new DataIntegrityViolationException("race"))
        .thenReturn(Optional.of(existing));
    when(sessions.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuthService service = new AuthServiceImpl(users, socials, sessions, tokens, transactions);

    AuthResult result = service.login(new GoogleIdentity("sub", "a@b.c", null, null));

    assertThat(tokens.decodeAccess(result.accessToken()).getSubject()).isEqualTo("8");
    assertThat(result.isNewUser()).isFalse();
  }
}
