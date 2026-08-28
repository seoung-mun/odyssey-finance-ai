package com.dacon.core.user.entity;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.user.dto.FinancialProfileInput;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** 사용자 ID를 기본키이자 외래키로 공유해 현재 월 금융정보를 저장한다. */
@Entity
@Table(name = "financial_profiles")
public class FinancialProfile {
  @Id private Integer userId;

  @MapsId
  @OneToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private long monthlyIncome;
  private long monthlyFixedCost;

  private Instant updatedAt;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected FinancialProfile() {}

  /**
   * 사용자 ID를 공유 기본키로 사용할 신규 금융 프로필을 만든다.
   *
   * @param user 프로필 소유 사용자
   */
  public FinancialProfile(UserAccount user) {
    this.user = user;
  }

  /**
   * 검증된 금액을 모두 교체하고 수정 시각을 현재 시각으로 갱신한다.
   *
   * @param input 저장할 금융 프로필 입력
   */
  public void update(FinancialProfileInput input) {
    monthlyIncome = input.monthlyIncome();
    monthlyFixedCost = input.monthlyFixedCost();
    updatedAt = Instant.now();
  }

  /**
   * 수정 시각을 제외한 저장값이 요청값과 모두 같은지 비교한다.
   *
   * @param input 비교할 금융 프로필 입력
   * @return 실질적인 값 변경이 없으면 {@code true}
   */
  public boolean matches(FinancialProfileInput input) {
    return monthlyIncome == input.monthlyIncome() && monthlyFixedCost == input.monthlyFixedCost();
  }

  /**
   * 계획 계산의 월 가용 재원에 사용할 소득을 제공한다.
   *
   * @return 현재 월소득(원)
   */
  public long monthlyIncome() {
    return monthlyIncome;
  }

  /**
   * 계획 계산에서 소득보다 먼저 차감할 고정비를 제공한다.
   *
   * @return 현재 월고정비(원)
   */
  public long monthlyFixedCost() {
    return monthlyFixedCost;
  }

  /**
   * 프로필 값이 마지막으로 교체된 시각을 제공한다.
   *
   * @return 마지막 값 저장 시각
   */
  public Instant updatedAt() {
    return updatedAt;
  }
}
