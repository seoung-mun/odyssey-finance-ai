# Qwen 3.5 2B 로컬 Docker 리소스 검증

측정일: 2026-08-25 18:08~18:38 KST

대상 모델: `qwen3.5:2b-q4_K_M`

결론: 로컬 메모리 smoke test 통과, `t4g.large` 운영 성능은 판정 보류

## 환경

- Apple M5 10코어, RAM 16GB, macOS 26.5.2
- Docker Desktop ARM64, VM 메모리 7.75GiB
- Analysis image 424MB
- Ollama image 6.97GB
- model blob 1.9GB
- 실제 FastAPI `/internal/explanations` 경로, 활성 추론 1개

로컬 Apple CPU 수치는 Graviton 지연시간을 증명하지 않는다. ARM64 이미지 호환성과 대략적
메모리 여유를 확인하는 자료로만 사용한다.

## 결과

| 조건 | 결과 |
|---|---|
| unloaded idle | Analysis 58.23MiB, Ollama 23.73MiB |
| warm idle | Analysis 56.8MiB, Ollama 2.77GiB |
| direct cold, n=1 | 20.726초, load 17.826초 |
| API cold, n=2 | 5.857초 / 15.071초, 모두 FALLBACK |
| warm API, n=10 | p50 3.133초, p95 5.086초, READY 3 / FALLBACK 7 |
| 동시 4건 | p50 12.784초, p95 14.958초, 최대 15.033초, 모두 FALLBACK |
| 30분 단일 worker | 259건, p50 4.965초, p95 15.047초, 최대 15.342초 |
| 30분 상태 | READY 65(25.1%), FALLBACK 194(74.9%), 실행 오류 0 |
| 30분 peak | Analysis 67.7MiB, Ollama 3,321.9MiB |
| 주요 5개 컨테이너 합계 | 부하 중 약 3.94GiB, OS와 Caddy 제외 |
| 종료 상태 | OOM 0, restart 0, Analysis running, Ollama healthy |

Ollama peak RSS는 10분 3,233.8MiB, 20분 3,314.7MiB, 30분 3,321.9MiB였다. 증가 폭이
줄었고 종료 warm RSS는 3.226GiB였으므로 30분 범위에서 지속적인 선형 증가는 관측되지
않았다. 30분을 넘는 memory leak이 없다는 뜻은 아니다.

## 확인된 위험

### 1. HTTP 전체 시간이 15초를 넘을 수 있음

30분 p95는 15.047초, 최대는 15.342초였다. FastAPI 내부 deadline 뒤의 응답 직렬화와 HTTP
반환 시간을 포함하면 호출 측이 정확히 15초에 끊을 때 안전한 FALLBACK도 받지 못할 수 있다.

### 2. 모델 장기 warm 유지가 보장되지 않음

마지막 요청 뒤 `ollama ps`에 약 4분의 잔여 상주 시간이 표시됐다. direct cold 20.726초는
15초보다 길기 때문에 idle 뒤 첫 요청은 실시간 READY보다 FALLBACK 가능성이 높다.

### 3. READY 비율이 낮음

30분 동안 READY는 25.1%였다. timeout뿐 아니라 숫자 가드레일 교정도 포함된 결과이므로
실제 payload별 실패 원인과 설명 품질을 staging에서 분리 측정해야 한다.

## Staging 판정 조건

- 전체 Compose를 8GiB 제한 안에서 실행하고 OS·Caddy를 포함한 메모리를 측정한다.
- cold와 warm을 분리해 각각 최소 30건을 실행한다.
- 동시 2건에서 활성 추론 1개와 대기 요청의 종료 시간을 확인한다.
- Spring→FastAPI 네트워크와 응답 직렬화를 포함한 timeout 여유를 확정한다.
- 실제 요청을 발생시키며 최소 30분 RSS, OOM과 restart를 기록한다.
- READY와 FALLBACK의 p50/p95 및 원인을 분리하고 READY 설명을 사람이 검토한다.

기준을 충족하지 못하면 GPU를 바로 추가하지 않고 실시간 설명을 끄거나 사전 생성 설명과
숫자 없는 템플릿 FALLBACK을 사용한다.
