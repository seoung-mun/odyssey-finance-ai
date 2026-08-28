package com.dacon.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.user.entity.UserProfile;
import jakarta.persistence.Column;
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
}
