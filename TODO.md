# TODO

기준일: 2026-08-26. 우선순위와 근거는 `docs/개발-로드맵.md`, 실행 증거는
`docs/QA-결과.md`, 합격 기준은 `docs/QA-가이드.md`를 따른다.

## P0 — 실제 MVP blocker

- [ ] Core의 Analysis용 `HttpClient`를 실제 Uvicorn과 호환되는 HTTP/1.1로 고정하고, 실제
  Uvicorn request body·응답·DB 저장을 자동 통합 테스트로 남긴다.
- [ ] nullable 거래 필터 쿼리를 실제 PostgreSQL에서 무필터·단일필터·복합필터로 검증하고
  `GET /transactions?limit=50` 500을 해소한다.
- [ ] E2E 인증이 production과 같은 refresh 흐름을 실제 HTTPS browser에서 사용하도록 구성하고,
  access token을 테스트 코드가 앱 내부 상태에 임의 주입하지 않게 한다.
- [ ] 실제 서비스 자동 기동 레인을 만든다. PostgreSQL·Redis는 loopback, Uvicorn·Spring·Web은
  고정되지 않은 격리 포트에서 health 대기 후 실행하고 소유 자원만 정리한다.
- [ ] 공개 API 31개를 실제 Spring HTTP로 호출해 mapping·status·DTO·소유권을 검증한다. 현재
  worktree의 신규 7개 API는 코드 존재만으로 완료 처리하지 않는다.

## P0 — 금융·트랜잭션·재계획

- [ ] 계획 생성 성공 경로에서 option 3개·모든 band·simulation이 한 transaction으로 저장되고
  잘못된 Analysis 응답은 부분 행 없이 실패하는지 실제 DB로 검증한다.
- [ ] drift 상세 계산의 int64 overflow를 제거하고 최대값을 실제 요청으로 재검증한다.
- [ ] 수동 infeasible 재계획을 OpenAPI대로 422 `PLAN_INFEASIBLE`로 반환한다.
- [ ] 예정지출 동시 수정에서 계산 snapshot과 최종 저장 행의 정합성을 보장한다.
- [ ] 실제 Redis 중단·재기동·pending reclaim·중복 delivery에서 설명 상태와 계획 transaction을
  검증한다.
- [x] linked·pending·unmatched 환불, 늦은 PAYMENT resolve, 월 집계, 중복 import를 실제
  PostgreSQL과 공개 API에서 검증했다.

## P0 — Web 실제 사용자 흐름

- [ ] route interception 없는 브라우저가 인증 → 온보딩 → 거래 → 목표 → 계획 → 옵션 선택 →
  대시보드 → 예정지출 → 재계획 → 새로고침을 끝까지 수행한다.
- [ ] 사용자 A/B를 테스트가 직접 생성하고 B의 A 자원 조회·수정·결정을 실제 browser/API에서
  거부한다. 외부 foreign ID가 없다고 skip하지 않는다.
- [ ] 존재하지 않는 달력 날짜를 input type 우회와 직접 fetch 모두에서 거부한다.
- [ ] 재계획 403·404·422를 구분하고 422에서도 현재 활성 계획과 수정 안내를 보존한다.
- [ ] 실제 auth 레인의 Playwright trace에서 token·cookie를 남기지 않도록 trace 정책을 분리한다.

## P1 — 운영 보안·배포

- [ ] 실제 API domain을 Caddy site address로 설정하고 HTTPS 인증서·80→443 redirect·cookie·
  Vercel rewrite를 실제 브라우저에서 검증한다.
- [ ] AWS에서 80/443 외 SG 차단, EBS와 snapshot/backup 암호화, 최소권한 DB role을 검증한다.
- [ ] JWT·DB·Redis·Analysis token을 운영 secret store로 주입하고 회전·폐기 절차를 만든다.
- [ ] 개발 secret 파일 권한을 0600으로 제한하고 QA 포트가 loopback인지 자동 사전 검사한다.
- [ ] 내부 서비스가 multi-host로 분리되면 Core↔Analysis/Ollama/DB/Redis TLS 또는 mTLS를 적용한다.
- [ ] 실제 Google 테스트 계정 로그인은 수동 1회만 하고 반복·다중 사용자는 자동 E2E 인증으로
  검증한다.

## P1 — 평가·성능

- [ ] 실제 Ollama와 staging 하드웨어에서 품질·지연·RSS·15초 fallback을 측정한다.
- [ ] rolling-origin 백테스트와 coverage를 고정 fixture·seed·명령으로 남긴다.
- [ ] 기능·QA가 녹색이 된 뒤 별도 staging에서만 부하 테스트와 성능 최적화를 시작한다.
