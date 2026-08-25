package com.dacon.core.transaction;

import com.dacon.core.transaction.dto.TransactionDtos.CategorySummary;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.MonthlySpending;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionInput;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionPage;
import java.time.OffsetDateTime;
import java.util.List;

/** 사용자 소유 거래의 멱등 적재, 커서 조회와 KST 월 집계를 제공한다. */
public interface TransactionService {
  /**
   * 외부 거래 ID를 멱등 키로 사용해 한 트랜잭션에서 일괄 적재한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param inputs 검증을 마친 거래 입력 목록
   * @return 실제 삽입 건수와 같은 사용자 안에서 중복으로 건너뛴 건수
   * @throws com.dacon.core.error.ApiException 연결 예정지출이 없거나 다른 사용자 소유인 경우
   */
  ImportResult importAll(int userId, List<TransactionInput> inputs);

  /**
   * 사용자의 거래를 ID 역순 커서 방식으로 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @param category 정확히 일치시킬 카테고리
   * @param cursor 직전 응답의 거래 ID 문자열
   * @param limit 페이지 최대 항목 수
   * @return 거래 항목과 다음 페이지 커서
   * @throws com.dacon.core.error.ApiException 커서가 정수 ID 형식이 아닌 경우
   */
  TransactionPage list(
      int userId,
      OffsetDateTime from,
      OffsetDateTime to,
      String category,
      String cursor,
      int limit);

  /**
   * 현재 KST 월을 포함한 최근 월별 순 소비와 부트스트랩 대상 소비를 조회한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param months 포함할 최근 달 수
   * @return DB 월별 집계 결과
   */
  List<MonthlySpending> monthlySummary(int userId, int months);

  /**
   * 현재 KST 월을 포함한 최근 기간의 카테고리별 월평균 순 소비를 계산한다.
   *
   * @param userId 인증된 내부 사용자 ID
   * @param months 평균의 분모와 조회 구간으로 사용할 달 수
   * @return 전체 및 카테고리별 반올림 월평균
   */
  CategorySummary categorySummary(int userId, int months);
}
