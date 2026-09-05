# PolicyArtifact candidate 70 검토 보고서

## 요약

- 정책 수: **70** (`PASS`, 정확히 70)
- unique `policyKey`: **70** (`PASS`, 정확히 70)
- source 수: **70**, unique `sourceKey`: **70**
- 정책당 chunk 수: **전부 1개**, 총 chunk 수: **70**
- embedding: 정책 chunk 70개와 query profile 8개가 **모두 실제 KURE-v1 1024차원, finite, non-zero, L2 normalized** (`PASS`)
- artifact byte size: **1,066,203 bytes** / 5 MiB(5,242,880 bytes), **PASS**
- review gate: `PENDING`, `importable=false`; 70개 version 모두 `reviewStatus=PENDING`
- calculation: 70개 모두 비계산 모드이며 `calculationRule=null`

## supportGoal별 수량

| supportGoal | 수량 |
|---|---|
| DORMITORY | 4 |
| GUARANTEE | 8 |
| JEONSE | 13 |
| MONTHLY_RENT | 17 |
| MOVING_COST | 8 |
| PUBLIC_RENTAL | 16 |
| PURCHASE | 3 |
| SUBSCRIPTION | 1 |

## source / provenance / hash 검증

- 입력 70건 각각에 대해 `SHA-256(canonical(original)) == apiRecordSha256`를 재계산했으며 **70/70 PASS**입니다.
- 위 hash를 `sources[].contentSha256`, chunk metadata의 `rawSha256`/`apiRecordSha256`과 대조했으며 **70/70 PASS**입니다.
- `textSha256`는 기존 pipeline의 `normalized()` 규칙을 `chunkContent`에 적용해 계산했고 **70/70 PASS**입니다.
- `locatorSha256`는 UTF-8 `sourceLocator`의 SHA-256으로 계산했고 **70/70 PASS**입니다.
- root는 현재 importer와 `PolicyTestArtifacts`의 Jackson canonicalization과 동일한 key 정렬·숫자 표기로 직렬화하고, manifest를 빈 문자열로 둔 canonical root의 SHA-256을 `manifestSha256`에 넣었습니다. canonical bytes + LF 재검증은 **PASS**입니다.
- importer 금지 보안 key를 NFKC 정규화 규칙으로 검사했고 **PASS**입니다. secret/API key/user data는 추가하지 않았습니다.
- provenance 원문은 입력의 canonical `original` API record입니다. 중복 원문은 artifact에 재수록하지 않았고, 사용자에게 노출되는 검색 chunk는 입력의 `chunkContent` 한 개만 사용했습니다.
- 이번 build는 입력에 기록된 API 수집 결과를 변환한 것으로, 67개 unique `officialUrl`을 다시 live fetch하거나 redirect/HTTP status를 재검증하지 않았습니다. `finalUrl`은 입력이 선택한 `officialUrl`을 보존하며 URL 공식성·도달성은 PENDING 인간 검토 항목입니다.

## current importer schema 검증

- 저장 artifact는 의도적으로 `reviewGate=PENDING/importable=false`, 각 version `reviewStatus=PENDING`이므로 현재 importer가 **거부해야 정상**입니다.
- PENDING gate 외 필드 관계, strict root/nested DTO shape, canonical encoding, manifest, 1024차원 finite vector, source 참조 완전성, locator/hash, non-calculable/null rule 조건을 current importer와 같은 규칙으로 사전 검사한 결과 **PASS**입니다.
- 현재 `PolicyArtifactImportService`의 실제 `parseAndValidate`/`validate`를 호출한 임시 JUnit 검증에서도 저장본은 PENDING gate에서만 거부됐고, gate와 70개 version의 review status만 APPROVED로 바꿔 manifest를 다시 계산한 복사본은 나머지 schema validation을 **PASS**했습니다. DB import는 수행하지 않았습니다.
- 최종 인간 승인 시 gate와 각 version review status를 별도 승인 절차로 바꿔야 하며, 이 보고서는 APPROVED를 위조하지 않습니다.

## officialUrl fallback

- 신청 URL(`original.aplyUrlAddr`) 직접 사용: **27건**
- fallback: **43건** (`refUrlAddr1` 41건, 신청·참고 URL이 모두 없어 청년정책 포털 홈 2건)
- HTTP URL은 2건이며 importer는 URL scheme을 검증하지 않지만 사람 검토가 필요합니다.

| policyKey | 정책명 | 분류 | 사유 | officialUrl |
|---|---|---|---|---|
| YOUTH_CENTER_20260318005400212189 | (광양시)광양학사(서울 공공기숙사) 운영 | REF_URL_1 | 신청 URL 없음 | https://gwangyang.go.kr/kor/ |
| YOUTH_CENTER_20260413005400212693 | 울산 청년 웰스테이 지원 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/ulsanyouth/bbs/view.do?bbsId=BBS_0000000000000316&mId=008001001000000000&dataId=56103 |
| YOUTH_CENTER_20260513005400213187 | 부산 전세보증금 반환보증 보증료 지원 | REF_URL_1 | 신청 URL 형식 보완 | https://www.busan.go.kr/depart/reguarantee01 |
| YOUTH_CENTER_20260506005400213142 | 인천 전세보증금반환보증 보증료 지원 | YOUTH_CENTER_HOME | 신청·참고 URL 없음 | https://www.youthcenter.go.kr/ |
| YOUTH_CENTER_20260413005400212694 | 전세보증금 반환보증 보증료 지원 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/ulsanyouth/bbs/view.do?bbsId=BBS_0000000000000316&mId=008001001000000000&dataId=56109 |
| YOUTH_CENTER_20260430005400212957 | 부산 전세피해 임차인 버팀목 전세자금 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.busan.go.kr/depart/charterdamage001/1662197 |
| YOUTH_CENTER_20260430005400212958 | 부산 전세피해 임차인 주거안정지원금 지원 | REF_URL_1 | 신청 URL 형식 보완 | https://www.busan.go.kr/depart/charterdamage001 |
| YOUTH_CENTER_20260504005400213020 | 서산시 청년 신혼부부 주택 전세자금 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youth.chungnam.go.kr/web/main/customSupp/M040-05/view?bizId=A20260402LC000000000003154 |
| YOUTH_CENTER_20260330005400212334 | 신혼부부 전세자금 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.wanju.go.kr/index.9is |
| YOUTH_CENTER_20260330005400212333 | 완주군 청년·신혼부부·다자녀가구 주택전세자금 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.wanju.go.kr/index.9is |
| YOUTH_CENTER_20260409005400212657 | 청년 주택임차보증금 이자지원 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/ulsanyouth/bbs/view.do?bbsId=BBS_0000000000000316&mId=008001001000000000&dataId=56102 |
| YOUTH_CENTER_20260504005400213064 | 태안 청년 신혼부부 주택자금 대출이자 지원 | REF_URL_1 | 신청 URL 없음 | https://youth.chungnam.go.kr/web/main/customSupp/M040-05/view?bizId=A20260401LC000000000003135 |
| YOUTH_CENTER_20260406005400212449 | (강화군) 인천시 청년월세 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoDetail.do?poly_seq=409 |
| YOUTH_CENTER_20260318005400212194 | (광양시)귀농귀촌 보금자리 조성 지원 | REF_URL_1 | 신청 URL 없음 | https://gwangyang.go.kr/ |
| YOUTH_CENTER_20260506005400213155 | (옹진군) 인천시 청년월세 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoDetail.do?poly_seq=267 |
| YOUTH_CENTER_20260325005400212266 | 2026년도 청년 신혼부부 월세지원사업 | REF_URL_1 | 신청 URL 형식 보완 | http://www.gbhome.kr |
| YOUTH_CENTER_20260313005400212139 | 강진품애(愛) 청년 주거비 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.gangjin.go.kr/www/government/notice/gosi |
| YOUTH_CENTER_20260409005400212655 | 신혼부부 가구 주거비 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/house/main.ulsan |
| YOUTH_CENTER_20260330005400212317 | 신혼부부 주거비용 지원 | REF_URL_1 | 신청 URL 없음 | https://www.usc.go.kr/ko/page.do?mnu_uid=594& |
| YOUTH_CENTER_20260409005400212656 | 울산 청년가구 주거비 지원사업 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/ulsanyouth/bbs/view.do?bbsId=BBS_0000000000000316&mId=008001001000000000&dataId=56101 |
| YOUTH_CENTER_20260326005400212297 | 익산형 청년월세 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youthforest.iksan.go.kr/index.iksan |
| YOUTH_CENTER_20260409005400212654 | 청년 월세 지원 | REF_URL_1 | 신청 URL 없음 | https://www.ulsan.go.kr/s/ulsanyouth/bbs/view.do?bbsId=BBS_0000000000000316&mId=008001001000000000&dataId=56099 |
| YOUTH_CENTER_20260408005400212631 | 청년 주거급여 분리지급 | REF_URL_1 | 신청 URL 형식 보완 | https://youth.gwangju.go.kr/www/50?siteId=www&policyId=1269&url=%2Fwww%2Fpolicy%2FgjYgPolicyView |
| YOUTH_CENTER_20260326005400212288 | 2026년 평택시 청년 전월세 중개보수료 감면사업 | REF_URL_1 | 신청 URL 없음 | https://www.pyeongtaek.go.kr/ |
| YOUTH_CENTER_20260806005400213321 | 서구 청년 천원 복비(부동산 중개보수) 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youth.gwangju.go.kr/www/50?siteId=www&policyId=1416&url=%2Fwww%2Fpolicy%2FgjYgPolicyView |
| YOUTH_CENTER_20260313005400212147 | 용인청년 부동산 중개 보수 감면 사업 | REF_URL_1 | 신청 URL 없음 | https://youth.yongin.go.kr/web/main/youthPolicy/view?bizId=A2026021000000000000055001 |
| YOUTH_CENTER_20260406005400212460 | 천원 복비(주택 중개보수 지원) | REF_URL_1 | 신청 URL 없음 | https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoDetail.do?poly_seq=398 |
| YOUTH_CENTER_20260504005400213057 | 태안 청년 이사비 지원 | REF_URL_1 | 신청 URL 없음 | https://youth.chungnam.go.kr/web/main/customSupp/M040-06/view?bizId=A20260401LC000000000003132 |
| YOUTH_CENTER_20260406005400212510 | 기존주택 매입 청년 임대사업 | REF_URL_1 | 신청 URL 없음 | https://www.incheon.go.kr/housing/hou020102 |
| YOUTH_CENTER_20260429005400212903 | 부산 신혼부부 럭키7하우스 지원사업 | REF_URL_1 | 신청 URL 형식 보완 | https://www.busan.go.kr/depart/house0203 |
| YOUTH_CENTER_20260406005400212473 | 삼성희망디딤돌 인천센터 운영 | REF_URL_1 | 신청 URL 없음 | https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoDetail.do?poly_seq=370 |
| YOUTH_CENTER_20260406005400212507 | 아이플러스(i+) 집드림_천원주택 | REF_URL_1 | 신청 URL 없음 | https://www.incheon.go.kr/housing/hou060101 |
| YOUTH_CENTER_20260406005400212478 | 인품 자립주택 운영 | REF_URL_1 | 신청 URL 없음 | https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoDetail.do?poly_seq=360 |
| YOUTH_CENTER_20260430005400213001 | 청년 셰어하우스 운영 | REF_URL_1 | 신청 URL 없음 | https://youth.chungnam.go.kr/web/main/customSupp/M040-05/view?bizId=A20260406LC000000000003187 |
| YOUTH_CENTER_20260325005400212276 | 청년단기주거공간 운영(금강장) | REF_URL_1 | 신청 URL 없음 | https://www.usc.go.kr/youth/page.do?mnu_uid=2731& |
| YOUTH_CENTER_20260325005400212275 | 청년복합주거공간 운영(금수장) | REF_URL_1 | 신청 URL 없음 | https://www.usc.go.kr/youth/page.do?mnu_uid=2024& |
| YOUTH_CENTER_20260330005400212315 | 청년쉐어하우스 조성 및 운영 | REF_URL_1 | 신청 URL 없음 | https://youth.wanju.go.kr/index.wanju |
| YOUTH_CENTER_20260406005400212509 | 청년층 임대주택 공급 확대(통합공공임대주택) | YOUTH_CENTER_HOME | 신청·참고 URL 없음 | https://www.youthcenter.go.kr/ |
| YOUTH_CENTER_20260325005400212274 | 청춘가 청년주거지 운영 | REF_URL_1 | 신청 URL 없음 | https://www.usc.go.kr/youth/page.do?cmd=258&bod_uid=124012&srchEnable=1&srchSDate=&srchKwd=&initDay=&srchColumn=&listType=&initYear=&srchVote=-1&initMonth=&pageNo=1&srchBgpUid=-1&srchEDate=&mnu_uid=2014& |
| YOUTH_CENTER_20260318005400212188 | (광양시)청년 주택자금 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://gwangyang.go.kr/ |
| YOUTH_CENTER_20260325005400212264 | 신혼부부·청년 주택구입 대출이자 지원사업 | REF_URL_1 | 신청 URL 없음 | https://youthforest.iksan.go.kr/board/view.iksan?boardId=BBS_0000015&menuCd=DOM_000000105002001000&paging=ok&startPage=1&dataSid=45005 |
| YOUTH_CENTER_20260406005400212437 | 아이플러스(i+) 집드림_1.0 이자지원 | REF_URL_1 | 신청 URL 없음 | https://www.incheon.go.kr/housing/hou060201 |
| YOUTH_CENTER_20260616005400113238 | 청년주택드림청약통장 | REF_URL_1 | 신청 URL 없음 | https://nhuf.molit.go.kr/FP/FP07/FP0701/FP07010301.jsp |

### HTTP URL 검토 대상

| policyKey | 정책명 | officialUrl |
|---|---|---|
| YOUTH_CENTER_20260325005400212266 | 2026년도 청년 신혼부부 월세지원사업 | http://www.gbhome.kr |
| YOUTH_CENTER_20260421005400212804 | 다가구 등 기존주택 매입임대 사업 | http://www.jpdc.co.kr |

## 기존 24개 대비 교체/추가

- exact `policyKey` overlap: **0건**
- exact title overlap: **0건**
- key 기준으로 신규 70건은 모두 추가입니다. 다만 이 파일은 기존 24건을 포함하지 않는 새 candidate이므로 artifact corpus 관점에서는 기존 24건 전체가 빠지고 70건으로 교체됩니다.
- exact URL overlap은 1건(청년주택드림청약통장 계열)이라 의미상 중복 여부는 인간 검토가 필요합니다.

### 기존 candidate에서 빠지는 24건

| policyKey | 정책명 |
|---|---|
| kr-youth-rent-2026 | 청년월세 지원사업 |
| kr-happy-housing | 행복주택 청년 계층 |
| kr-youth-jeonse-rental | 청년 기존주택 전세임대 |
| kr-hf-youth-jeonse-guarantee | 무주택 청년 특례전세자금보증 |
| kr-youth-beotimmok | 청년전용 버팀목전세자금 |
| kr-youth-deposit-monthly-loan | 청년전용 보증부월세대출 |
| seoul-youth-rent-2026 | 2026년 서울시 청년월세지원 |
| seoul-moving-cost-2026 | 2026년 청년 부동산 중개보수 및 이사비 지원 |
| seoul-youth-deposit-interest-20260605 | 청년 임차보증금 이자지원 |
| seoul-youth-safe-housing-public-2026-2 | 2026년 2차 청년안심주택 공공임대 |
| seoul-youth-safe-housing-private | 청년안심주택 공공지원민간임대 |
| gg-gh-youth-purchase-rental-2026-1 | 2026년 1차 GH 청년 매입임대주택 |
| gg-happy-housing-2026-h1-extra | 2026년 상반기 경기행복주택 예비입주자 추가모집 |
| gg-anseong-moving-cost-2026 | 안성시 청년 부동산 중개수수료 및 이사비 지원 |
| gg-hwaseong-deposit-interest-2026-h2 | 화성시 청년 전월세 보증금 대출이자 지원 |
| gg-pocheon-deposit-interest-2026 | 포천시 청년가구 전월세 보증금 대출이자 지원 |
| incheon-youth-rent-35-39-2026 | 인천형 청년월세 지원사업 35~39세 |
| incheon-youth-deposit-interest-2026 | 인천시 청년 주택임차보증금 이자 지원 |
| incheon-ih-youth-purchase-rental-2026 | 2026년 iH 기존주택 매입임대 청년형 |
| incheon-lh-youth-purchase-rental-2026-3 | LH 인천지역 2026년 3차 청년 매입임대 |
| incheon-jemulpo-welcome-moving-2026 | 제물포구 청년 웰컴페이 이사비 |
| kr-youth-dream-didimdol | 청년 주택드림 디딤돌 대출 |
| kr-youth-dream-subscription | 청년 주택드림 청약통장 |
| kr-happy-dormitory | 행복기숙사 지원사업 |

### 새 candidate에 추가되는 70건

| policyKey | 정책명 | supportGoal |
|---|---|---|
| YOUTH_CENTER_20260123005400112079 | 대학생 연합생활관(은행권,고양) | DORMITORY |
| YOUTH_CENTER_20260313005400212124 | 청년신혼부부 월세지원 사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260313005400212139 | 강진품애(愛) 청년 주거비 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260313005400212147 | 용인청년 부동산 중개 보수 감면 사업 | MOVING_COST |
| YOUTH_CENTER_20260318005400212188 | (광양시)청년 주택자금 대출이자 지원사업 | PURCHASE |
| YOUTH_CENTER_20260318005400212189 | (광양시)광양학사(서울 공공기숙사) 운영 | DORMITORY |
| YOUTH_CENTER_20260318005400212193 | 청년 1인가구 전월세 안심계약 지원 | JEONSE |
| YOUTH_CENTER_20260318005400212194 | (광양시)귀농귀촌 보금자리 조성 지원 | MONTHLY_RENT |
| YOUTH_CENTER_20260325005400212264 | 신혼부부·청년 주택구입 대출이자 지원사업 | PURCHASE |
| YOUTH_CENTER_20260325005400212266 | 2026년도 청년 신혼부부 월세지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260325005400212269 | 2026년 전세보증금반환보증 보증료 지원사업 | GUARANTEE |
| YOUTH_CENTER_20260325005400212274 | 청춘가 청년주거지 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260325005400212275 | 청년복합주거공간 운영(금수장) | PUBLIC_RENTAL |
| YOUTH_CENTER_20260325005400212276 | 청년단기주거공간 운영(금강장) | PUBLIC_RENTAL |
| YOUTH_CENTER_20260326005400212288 | 2026년 평택시 청년 전월세 중개보수료 감면사업 | MOVING_COST |
| YOUTH_CENTER_20260326005400212297 | 익산형 청년월세 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260330005400212315 | 청년쉐어하우스 조성 및 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260330005400212317 | 신혼부부 주거비용 지원 | MONTHLY_RENT |
| YOUTH_CENTER_20260330005400212333 | 완주군 청년·신혼부부·다자녀가구 주택전세자금 대출이자 지원사업 | JEONSE |
| YOUTH_CENTER_20260330005400212334 | 신혼부부 전세자금 대출이자 지원사업 | JEONSE |
| YOUTH_CENTER_20260406005400212437 | 아이플러스(i+) 집드림_1.0 이자지원 | PURCHASE |
| YOUTH_CENTER_20260406005400212449 | (강화군) 인천시 청년월세 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260406005400212457 | (동구) 2026년 청년 웰컴페이(이사비) 지원사업 | MOVING_COST |
| YOUTH_CENTER_20260406005400212460 | 천원 복비(주택 중개보수 지원) | MOVING_COST |
| YOUTH_CENTER_20260406005400212463 | (중구) 2026년 중구 청년 이사비 지원사업 | MOVING_COST |
| YOUTH_CENTER_20260406005400212468 | 청년 주택임차보증금 이자 지원 대출연장 | JEONSE |
| YOUTH_CENTER_20260406005400212473 | 삼성희망디딤돌 인천센터 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260406005400212478 | 인품 자립주택 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260406005400212507 | 아이플러스(i+) 집드림_천원주택 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260406005400212509 | 청년층 임대주택 공급 확대(통합공공임대주택) | PUBLIC_RENTAL |
| YOUTH_CENTER_20260406005400212510 | 기존주택 매입 청년 임대사업 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260408005400212631 | 청년 주거급여 분리지급 | MONTHLY_RENT |
| YOUTH_CENTER_20260408005400212633 | 전세보증금반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260409005400212654 | 청년 월세 지원 | MONTHLY_RENT |
| YOUTH_CENTER_20260409005400212655 | 신혼부부 가구 주거비 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260409005400212656 | 울산 청년가구 주거비 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260409005400212657 | 청년 주택임차보증금 이자지원 | JEONSE |
| YOUTH_CENTER_20260413005400212693 | 울산 청년 웰스테이 지원 | DORMITORY |
| YOUTH_CENTER_20260413005400212694 | 전세보증금 반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260421005400212786 | 일하는 청년 보금자리 지원 사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260421005400212799 | 전세보증금반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260421005400212800 | 신혼부부 등 주택전세 연월세자금 대출이자 지원 | JEONSE |
| YOUTH_CENTER_20260421005400212804 | 다가구 등 기존주택 매입임대 사업 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260421005400212805 | 공공임대주택 공급 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260422005400212846 | 제주 청년 희망충전 월세 지원 | MONTHLY_RENT |
| YOUTH_CENTER_20260422005400212847 | 공공주택 임대차보증금 지원사업 | JEONSE |
| YOUTH_CENTER_20260429005400212903 | 부산 신혼부부 럭키7하우스 지원사업 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260429005400212904 | 부산 평생함께 청년모두가 주거비 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260429005400212905 | 부산 청년 맞춤형 민간임대주택 공급(희망더함주택) | PUBLIC_RENTAL |
| YOUTH_CENTER_20260430005400212957 | 부산 전세피해 임차인 버팀목 전세자금 대출이자 지원사업 | JEONSE |
| YOUTH_CENTER_20260430005400212958 | 부산 전세피해 임차인 주거안정지원금 지원 | JEONSE |
| YOUTH_CENTER_20260430005400212969 | 청년단기숙소 지원사업 | DORMITORY |
| YOUTH_CENTER_20260430005400212984 | 청년공유주택 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260430005400212998 | 청년 창업농 주거지원 사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260430005400213001 | 청년 셰어하우스 운영 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260504005400113081 | 전세보증금반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260504005400213020 | 서산시 청년 신혼부부 주택 전세자금 대출이자 지원사업 | JEONSE |
| YOUTH_CENTER_20260504005400213057 | 태안 청년 이사비 지원 | MOVING_COST |
| YOUTH_CENTER_20260504005400213064 | 태안 청년 신혼부부 주택자금 대출이자 지원 | JEONSE |
| YOUTH_CENTER_20260506005400213142 | 인천 전세보증금반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260506005400213155 | (옹진군) 인천시 청년월세 지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260513005400213186 | 부산 전세피해 임차인 민간주택 월세지원사업 | MONTHLY_RENT |
| YOUTH_CENTER_20260513005400213187 | 부산 전세보증금 반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260513005400213198 | 부산 청년 중개보수 및 이사비 지원 | MOVING_COST |
| YOUTH_CENTER_20260513005400213200 | 부산 청년 임차보증금 대출 및 대출이자 지원(머물자리론) | JEONSE |
| YOUTH_CENTER_20260521005400213209 | 청년온가 2026년 정기 입주자 모집 재공고 | PUBLIC_RENTAL |
| YOUTH_CENTER_20260527005400113223 | 전세보증금반환보증 보증료 지원 | GUARANTEE |
| YOUTH_CENTER_20260616005400113238 | 청년주택드림청약통장 | SUBSCRIPTION |
| YOUTH_CENTER_20260722005400213273 | 2026년 신혼부부 전세자금 대출이자 지원 안내 | JEONSE |
| YOUTH_CENTER_20260806005400213321 | 서구 청년 천원 복비(부동산 중개보수) 지원사업 | MOVING_COST |
