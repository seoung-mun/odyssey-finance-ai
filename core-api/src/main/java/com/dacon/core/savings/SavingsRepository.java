package com.dacon.core.savings;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SavingsRepository {
  private final JdbcClient jdbc;

  public SavingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<ProductRow> eligibleProducts() {
    return jdbc.sql(
            """
        SELECT id,fin_co_no,fin_prdt_cd,kor_co_nm,fin_prdt_nm,join_deny,max_limit
          FROM savings_products
         WHERE active=true AND join_deny='1'
         ORDER BY fin_co_no,fin_prdt_cd
        """)
        .query(
            (row, index) ->
                new ProductRow(
                    row.getLong("id"),
                    row.getString("fin_co_no"),
                    row.getString("fin_prdt_cd"),
                    row.getString("kor_co_nm"),
                    row.getString("fin_prdt_nm"),
                    row.getString("join_deny"),
                    maxLimit(row)))
        .list();
  }

  public Optional<ProductRow> product(long productId) {
    return jdbc.sql(
            """
            SELECT id,fin_co_no,fin_prdt_cd,kor_co_nm,fin_prdt_nm,join_deny,max_limit
              FROM savings_products WHERE id=:id AND active=true
            """)
        .param("id", productId)
        .query(
            (row, index) ->
                new ProductRow(
                    row.getLong("id"),
                    row.getString("fin_co_no"),
                    row.getString("fin_prdt_cd"),
                    row.getString("kor_co_nm"),
                    row.getString("fin_prdt_nm"),
                    row.getString("join_deny"),
                    maxLimit(row)))
        .optional();
  }

  public List<OptionRow> simpleOptions(long productId) {
    return jdbc.sql(
            """
        SELECT id,product_id,rsrv_type,rsrv_type_nm,save_trm,intr_rate,intr_rate2
          FROM savings_product_options
         WHERE product_id=:productId AND active=true AND intr_rate_type='S'
         ORDER BY save_trm,rsrv_type,id
        """)
        .param("productId", productId)
        .query(
            (row, index) ->
                new OptionRow(
                    row.getLong("id"),
                    row.getLong("product_id"),
                    row.getString("rsrv_type"),
                    row.getString("rsrv_type_nm"),
                    row.getInt("save_trm"),
                    row.getBigDecimal("intr_rate"),
                    row.getBigDecimal("intr_rate2")))
        .list();
  }

  public Optional<OptionRow> simpleOption(long productId, long optionId) {
    return simpleOptions(productId).stream().filter(value -> value.id() == optionId).findFirst();
  }

  public List<ConditionRow> ruleConditions(long productId) {
    return jdbc.sql(
            """
        SELECT id,product_id,label,bonus_rate
          FROM savings_product_conditions
         WHERE product_id=:productId AND active=true AND source='RULE' AND review_needed=false
         ORDER BY id
        """)
        .param("productId", productId)
        .query(
            (row, index) ->
                new ConditionRow(
                    row.getLong("id"),
                    row.getLong("product_id"),
                    row.getString("label"),
                    row.getBigDecimal("bonus_rate")))
        .list();
  }

  public List<ConditionRow> selectedRuleConditions(long productId, Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
        SELECT id,product_id,label,bonus_rate
          FROM savings_product_conditions
         WHERE product_id=:productId AND id IN (:ids)
           AND active=true AND source='RULE' AND review_needed=false
         ORDER BY id
        """)
        .param("productId", productId)
        .param("ids", ids)
        .query(
            (row, index) ->
                new ConditionRow(
                    row.getLong("id"),
                    row.getLong("product_id"),
                    row.getString("label"),
                    row.getBigDecimal("bonus_rate")))
        .list();
  }

  private static Long maxLimit(ResultSet row) throws SQLException {
    BigDecimal value = row.getBigDecimal("max_limit");
    return value == null ? null : value.longValueExact();
  }

  public record ProductRow(
      long id,
      String finCoNo,
      String finPrdtCd,
      String bankName,
      String productName,
      String joinDeny,
      Long maxLimit) {}

  public record OptionRow(
      long id,
      long productId,
      String reserveType,
      String reserveTypeName,
      int termMonths,
      BigDecimal baseRate,
      BigDecimal maximumRate) {}

  public record ConditionRow(long id, long productId, String label, BigDecimal bonusRate) {}
}
