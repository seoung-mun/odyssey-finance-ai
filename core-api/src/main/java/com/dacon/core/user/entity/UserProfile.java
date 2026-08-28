package com.dacon.core.user.entity;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.user.dto.UserDtos.ProfileInput;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자 ID를 공유 기본키로 사용해 선택적 생년월일과 5자리 시군구 코드를 저장한다. */
@Entity
@Table(name = "user_profiles")
public class UserProfile {
  @Id private Integer userId;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private LocalDate birthDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "region_code", columnDefinition = "char(5)", length = 5)
  private String regionCode;

  private Instant createdAt;
  private Instant updatedAt;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected UserProfile() {}

  /**
   * 신규 인적 프로필을 만들고 생성·수정 시각을 설정한다.
   *
   * @param user 프로필 소유 사용자
   * @param input 검증을 마친 인적 프로필 입력
   */
  public UserProfile(UserAccount user, ProfileInput input) {
    this.user = user;
    createdAt = Instant.now();
    update(input);
  }

  /**
   * 생년월일과 지역 코드를 교체하고 수정 시각을 현재 시각으로 갱신한다.
   *
   * @param input 저장할 인적 프로필 입력
   */
  public void update(ProfileInput input) {
    birthDate = input.birthDate();
    regionCode = input.regionCode();
    updatedAt = Instant.now();
  }

  /**
   * 연령 기반 사용자 정보를 제공한다.
   *
   * @return 생년월일, 입력하지 않았으면 {@code null}
   */
  public LocalDate birthDate() {
    return birthDate;
  }

  /**
   * 정책 지역 필터에 사용할 시군구 코드를 padding 없이 제공한다.
   *
   * @return CHAR 컬럼 padding을 제거한 5자리 지역 코드, 입력하지 않았으면 {@code null}
   */
  public String regionCode() {
    return regionCode == null ? null : regionCode.trim();
  }

  /**
   * 인적 프로필이 마지막으로 저장된 시각을 제공한다.
   *
   * @return 마지막 저장 시각
   */
  public Instant updatedAt() {
    return updatedAt;
  }
}
