package com.dacon.core.goal;

import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import java.util.List;

/** 사용자 소유 금융 목표와 예정지출 조회·생성 유스케이스의 트랜잭션 경계다. */
public interface GoalService {
  /**
   * 사용자 소유 목표 한 건을 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param goalId 조회할 목표 ID
   * @return 소유권이 확인된 목표
   * @throws com.dacon.core.error.ApiException 목표가 없거나 다른 사용자 소유인 경우
   */
  GoalResponse goal(int userId, int goalId);

  /**
   * 사용자의 목표를 최신 생성 순으로 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param status 조회할 상태, 전체 상태면 {@code null}
   * @return 해당 사용자 목표 목록
   */
  List<GoalResponse> goals(int userId, String status);

  /**
   * 사용자에게 ACTIVE 목표를 하나 생성한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param input 검증을 마친 목표 입력
   * @return 생성된 목표
   * @throws com.dacon.core.error.ApiException 사용자가 없거나 목표일이 유효하지 않거나 ACTIVE 목표가 이미 있는 경우
   */
  GoalResponse create(int userId, GoalRequest input);

  /**
   * 사용자의 예정지출과 연결 거래의 순액 집계를 날짜 순으로 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param status 조회할 상태, 전체 상태면 {@code null}
   * @return 예정지출별 매칭 건수와 PAYMENT-REFUND 순액
   */
  List<ScheduledExpenseResponse> scheduledExpenses(int userId, String status);
}
