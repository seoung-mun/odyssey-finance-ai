package com.dacon.core.plan;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.transaction.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 재계획을 촉발한 사건과 사용자·목표·이전 및 제안 계획의 관계를 저장하는 엔티티다. */
@Entity
@Table(name = "replan_events")
public class ReplanEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "goal_id", nullable = false)
  private FinancialGoal goal;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "source_plan_version_id", nullable = false)
  private PlanVersion sourcePlanVersion;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "proposed_plan_version_id")
  private PlanVersion proposedPlanVersion;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_transaction_id")
  private Transaction sourceTransaction;

  private String triggerType;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode triggerDetails;

  private String userDecision;
  private Instant createdAt;
  private Instant decidedAt;

  /** JPA가 엔티티를 복원할 때만 사용하는 생성자다. */
  protected ReplanEvent() {}

  /** {@return 영속화된 재계획 사건 식별자} */
  public int id() {
    return id;
  }

  /** {@return 정기 점검, 과소비 등 재계획을 촉발한 종류} */
  public String triggerType() {
    return triggerType;
  }
}
