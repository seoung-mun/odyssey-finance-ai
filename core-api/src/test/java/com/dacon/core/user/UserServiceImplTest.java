package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.SocialAccountRepository;
import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.transaction.TransactionRepository;
import com.dacon.core.user.entity.UserProfile;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.dacon.core.user.repository.UserProfileRepository;
import jakarta.persistence.Column;
import java.util.Optional;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class UserServiceImplTest {
  @Test
  void financialProfileUpsertKeepsLoadedUserManagedForSharedPrimaryKeyInsert() throws Exception {
    assertThat(
            UserServiceImpl.class
                .getMethod(
                    "upsertFinancialProfile",
                    int.class,
                    com.dacon.core.user.dto.FinancialProfileInput.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
  }

  @Test
  void regionCodeMapsToFixedFiveCharacterColumn() throws Exception {
    Column column = UserProfile.class.getDeclaredField("regionCode").getAnnotation(Column.class);

    assertThat(column).isNotNull();
    assertThat(column.columnDefinition()).isEqualTo("char(5)");
    assertThat(column.length()).isEqualTo(5);
    JdbcTypeCode jdbcType =
        UserProfile.class.getDeclaredField("regionCode").getAnnotation(JdbcTypeCode.class);
    assertThat(jdbcType).isNotNull();
    assertThat(jdbcType.value()).isEqualTo(SqlTypes.CHAR);
  }

  @Test
  void sampleExternalIdIsDeterministicPerUserAndSeedVersion() {
    assertThat(UserServiceImpl.sampleExternalId(7, "2025-01", 3))
        .isEqualTo(UserServiceImpl.sampleExternalId(7, "2025-01", 3))
        .isNotEqualTo(UserServiceImpl.sampleExternalId(8, "2025-01", 3));
  }

  @Test
  void sampleLoadRejectsAccountContainingUserData() {
    UserAccountRepository users = mock(UserAccountRepository.class);
    SocialAccountRepository social = mock(SocialAccountRepository.class);
    UserProfileRepository profiles = mock(UserProfileRepository.class);
    FinancialProfileRepository financial = mock(FinancialProfileRepository.class);
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    ScheduledExpenseRepository scheduled = mock(ScheduledExpenseRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    when(users.findByIdForUpdate(4)).thenReturn(Optional.of(new UserAccount()));
    when(financial.existsById(4)).thenReturn(true);
    UserService service =
        new UserServiceImpl(users, social, profiles, financial, goals, scheduled, transactions);

    assertThatThrownBy(() -> service.loadSample(4))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("SAMPLE_DATA_CONFLICT");
  }
}
