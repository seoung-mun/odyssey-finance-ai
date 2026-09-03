// Source: 행정표준코드관리시스템 https://www.code.go.kr/stdcode/regCodeL.do
// 시군구: https://www.code.go.kr/stdcode/sggCodeIL.do (sidoCd, searchOk=0), 조회일 2026-09-02.
// 공식 응답의 현존 이름+코드 쌍을 제거하거나 임의 보정하지 않는다.
import data from "./regionCodes.generated.json";

export const sidoRegions = data.sidos;
export const sigunguRegions = data.regions;
