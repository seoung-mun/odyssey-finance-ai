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

/** 사용자 인적 프로필과 users의 일대일 관계를 저장한다. */
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

  /** JPA 복원용 생성자다. */
  protected UserProfile() {}

  /** 사용자와 입력으로 프로필을 만든다. */
  public UserProfile(UserAccount user, ProfileInput input) {
    this.user = user;
    createdAt = Instant.now();
    update(input);
  }

  /** 검증된 입력을 반영한다. */
  public void update(ProfileInput input) {
    birthDate = input.birthDate();
    regionCode = input.regionCode();
    updatedAt = Instant.now();
  }

  public LocalDate birthDate() {
    return birthDate;
  }

  public String regionCode() {
    return regionCode == null ? null : regionCode.trim();
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
