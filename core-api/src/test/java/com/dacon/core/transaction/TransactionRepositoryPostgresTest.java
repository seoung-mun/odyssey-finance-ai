package com.dacon.core.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class TransactionRepositoryPostgresTest extends com.dacon.core.PostgresIntegrationTestSupport {
  @Autowired private TransactionRepository transactions;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void insertTransactions() {
    jdbc.update("insert into users(id) values (9911)");
    jdbc.update(
        "insert into transactions(user_id, transaction_at, amount, transaction_type, category, source_id, external_transaction_id) values (9911, '2026-08-01T00:00:00+09:00', 1000, 'PAYMENT', '식비', 'MANUAL', 'food-1')");
    jdbc.update(
        "insert into transactions(user_id, transaction_at, amount, transaction_type, category, source_id, external_transaction_id) values (9911, '2026-08-02T00:00:00+09:00', 2000, 'PAYMENT', '교통', 'MANUAL', 'travel-1')");
  }

  @Test
  void findPageAcceptsAllNullFiltersOnPostgres() {
    assertThat(
            transactions.findPage(
                9911,
                null,
                null,
                null,
                Long.MAX_VALUE,
                org.springframework.data.domain.PageRequest.of(0, 10)))
        .hasSize(2);
  }

  @Test
  void findPageAcceptsCategoryFilterOnPostgres() {
    assertThat(
            transactions.findPage(
                9911,
                null,
                null,
                "식비",
                Long.MAX_VALUE,
                org.springframework.data.domain.PageRequest.of(0, 10)))
        .extracting(Transaction::category)
        .containsExactly("식비");
  }

  @Test
  void findPageAcceptsDateRangeOnPostgres() {
    assertThat(
            transactions.findPage(
                9911,
                OffsetDateTime.parse("2026-08-02T00:00:00+09:00"),
                OffsetDateTime.parse("2026-08-03T00:00:00+09:00"),
                null,
                Long.MAX_VALUE,
                org.springframework.data.domain.PageRequest.of(0, 10)))
        .extracting(Transaction::category)
        .containsExactly("교통");
  }
}
