package com.dacon.core.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class DemoPresetProfilePostgresTest extends com.dacon.core.PostgresIntegrationTestSupport {
  @Autowired private DemoService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void persistsEveryPolicyDemoProfileWithoutChangingItsTransactionPreset() {
    verifyPreset("youth", LocalDate.of(1997, 1, 1), "12240");
    verifyPreset("middle", LocalDate.of(1997, 1, 1), "52140");
    verifyPreset("senior", LocalDate.of(1991, 1, 1), "50110");
  }

  private void verifyPreset(String testerId, LocalDate birthDate, String regionCode) {
    Integer userId =
        jdbc.queryForObject("insert into users default values returning id", Integer.class);

    DemoDtos.DemoSeedResponse result = service.seed(userId, testerId);

    assertThat(result.testerId()).isEqualTo(testerId);
    assertThat(
            jdbc.queryForObject(
                "select birth_date from user_profiles where user_id=?", LocalDate.class, userId))
        .isEqualTo(birthDate);
    assertThat(
            jdbc.queryForObject(
                "select btrim(region_code) from user_profiles where user_id=?",
                String.class,
                userId))
        .isEqualTo(regionCode);
    assertThat(
            jdbc.queryForObject(
                "select tester_id from demo_seed_state where user_id=?", String.class, userId))
        .isEqualTo(testerId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from transactions where user_id=? and source_id='DEMO'",
                Long.class,
                userId))
        .isPositive();
  }
}
