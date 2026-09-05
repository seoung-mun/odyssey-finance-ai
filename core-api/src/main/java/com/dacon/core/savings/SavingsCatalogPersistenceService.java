package com.dacon.core.savings;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsCatalogPersistenceService {
  private final JdbcClient jdbc;
  private final SpecialConditionParser parser;

  public SavingsCatalogPersistenceService(JdbcClient jdbc, SpecialConditionParser parser) {
    this.jdbc = jdbc;
    this.parser = parser;
  }

  @Transactional
  public void replaceActiveSnapshot(SavingsCatalogSnapshot snapshot) {
    jdbc.sql("UPDATE savings_product_options SET active=false, updated_at=now() WHERE active")
        .update();
    jdbc.sql("UPDATE savings_product_conditions SET active=false, updated_at=now() WHERE active")
        .update();
    jdbc.sql("UPDATE savings_products SET active=false, updated_at=now() WHERE active").update();
    Map<String, Long> productIds = new HashMap<>();
    for (SavingsCatalogSnapshot.Product product : snapshot.products()) {
      long productId = upsertProduct(product);
      productIds.put(key(product.finCoNo(), product.finPrdtCd()), productId);
      for (SavingsCatalogSnapshot.Condition condition : parser.parse(product.specialCondition())) {
        upsertCondition(productId, condition);
      }
    }
    for (SavingsCatalogSnapshot.Option option : snapshot.options()) {
      Long productId = productIds.get(key(option.finCoNo(), option.finPrdtCd()));
      if (productId != null) {
        upsertOption(productId, option);
      }
    }
  }

  private long upsertProduct(SavingsCatalogSnapshot.Product product) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("finCoNo", product.finCoNo())
            .addValue("finPrdtCd", product.finPrdtCd())
            .addValue("dclsMonth", product.dclsMonth())
            .addValue("bankName", product.bankName())
            .addValue("productName", product.productName())
            .addValue("joinWay", product.joinWay())
            .addValue("maturityInterest", product.maturityInterest())
            .addValue("specialCondition", product.specialCondition())
            .addValue("joinDeny", product.joinDeny())
            .addValue("joinMember", product.joinMember())
            .addValue("maxLimit", product.maxLimit(), Types.BIGINT);
    Long id =
        jdbc.sql(
                """
            INSERT INTO savings_products
              (fin_co_no,fin_prdt_cd,dcls_month,kor_co_nm,fin_prdt_nm,join_way,mtrt_int,
               spcl_cnd,join_deny,join_member,max_limit,active)
            VALUES
              (:finCoNo,:finPrdtCd,:dclsMonth,:bankName,:productName,:joinWay,:maturityInterest,
               :specialCondition,:joinDeny,:joinMember,:maxLimit,true)
            ON CONFLICT (fin_co_no,fin_prdt_cd) DO UPDATE SET
              dcls_month=EXCLUDED.dcls_month, kor_co_nm=EXCLUDED.kor_co_nm,
              fin_prdt_nm=EXCLUDED.fin_prdt_nm, join_way=EXCLUDED.join_way,
              mtrt_int=EXCLUDED.mtrt_int, spcl_cnd=EXCLUDED.spcl_cnd,
              join_deny=EXCLUDED.join_deny, join_member=EXCLUDED.join_member,
              max_limit=EXCLUDED.max_limit, active=true, updated_at=now()
            RETURNING id
            """)
            .params(parameters.getValues())
            .query(Long.class)
            .single();
    if (id == null) {
      throw new IllegalStateException("Finlife 상품 upsert가 ID를 반환하지 않았습니다.");
    }
    return id;
  }

  private void upsertCondition(long productId, SavingsCatalogSnapshot.Condition condition) {
    jdbc.sql(
            """
        INSERT INTO savings_product_conditions
          (product_id,label,bonus_rate,source,review_needed,raw_fragment,active)
        VALUES (:productId,:label,:bonusRate,'RULE',false,:rawFragment,true)
        ON CONFLICT (product_id,source,label) DO UPDATE SET
          bonus_rate=EXCLUDED.bonus_rate, review_needed=false,
          raw_fragment=EXCLUDED.raw_fragment, active=true, updated_at=now()
        """)
        .param("productId", productId)
        .param("label", condition.label())
        .param("bonusRate", condition.bonusRate())
        .param("rawFragment", condition.rawFragment())
        .update();
  }

  private void upsertOption(long productId, SavingsCatalogSnapshot.Option option) {
    jdbc.sql(
            """
        INSERT INTO savings_product_options
          (product_id,intr_rate_type,intr_rate_type_nm,rsrv_type,rsrv_type_nm,
           save_trm,intr_rate,intr_rate2,active)
        VALUES
          (:productId,:rateType,:rateTypeName,:reserveType,:reserveTypeName,
           :termMonths,:baseRate,:maximumRate,true)
        ON CONFLICT (product_id,intr_rate_type,rsrv_type,save_trm) DO UPDATE SET
          intr_rate_type_nm=EXCLUDED.intr_rate_type_nm,
          rsrv_type_nm=EXCLUDED.rsrv_type_nm,
          intr_rate=EXCLUDED.intr_rate, intr_rate2=EXCLUDED.intr_rate2,
          active=true, updated_at=now()
        """)
        .param("productId", productId)
        .param("rateType", option.rateType())
        .param("rateTypeName", option.rateTypeName())
        .param("reserveType", option.reserveType())
        .param("reserveTypeName", option.reserveTypeName())
        .param("termMonths", option.termMonths())
        .param("baseRate", option.baseRate())
        .param("maximumRate", option.maximumRate())
        .update();
  }

  private String key(String company, String product) {
    return company + '\u0000' + product;
  }
}
