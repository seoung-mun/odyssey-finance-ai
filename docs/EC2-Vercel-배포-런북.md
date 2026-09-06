# 오늘(2026-09-06) 배포 런북 — Vercel 프론트 + EC2 Docker 백엔드

기준 시각: 2026-09-06. **심사 URL 운영 기간은 2026-09-07 11:00 KST부터 2026-09-11 23:59
KST까지이고, 그 기간에는 심사용 환경 배포·인스턴스 변경이 금지된다**(`README.md`,
`CLAUDE.md`). 즉 오늘 안에 안정화까지 끝내야 하고, 내일 11시 이후에는 "실시간으로 에러
잡기"를 심사 인스턴스에서 하면 안 된다 — 실시간 대응은 오늘 배포 직후 시간대에만
유효하다. 아래 순서를 그대로 따라가면서 각 단계 완료 시 체크한다.

배포 대상 AWS 계정: **팀원이 제공한 별도 계정**(자기 프리티어, ID/PW로 콘솔 로그인).
인스턴스 생성(2~3번)은 AWS 콘솔에서 클릭으로 진행하고, 이미지 빌드·전송·기동(4번 이후)은
로컬/EC2 터미널에서 명령으로 진행한다.

## 0. 배포 브랜치 — `prod` (develop과 동기화 완료)

`prod`는 `develop`을 그대로 따라가되(뒤처진 커밋 0개), 배포에 필요 없는 문서·테스트·
QA 스크립트·연구용 데이터 223개 파일만 뺀 상태다(`ce7a037` → `30b80af`). Caddy는
`Caddyfile.backend`로 core-api만 리버스 프록시하고 정적 Web은 안 만든다(Vercel이
프론트를 맡음). `web/vercel.ts`·`Caddyfile.backend`·
`data/policy/policy-artifact-calculable-approved-23.json` 3개는 이 배포에 필요해서
pruning에서 제외되어 있다.

- [x] **결정: `prod` 기준으로 배포한다**

## 0-1. 인스턴스 크기 — `t3.small`(2GB), 실측으로 검증 완료

`prod` 브랜치를 로컬에서 실제로 빌드·기동해 `docker stats`로 쟀다(2026-09-06 실측,
Mac Docker Desktop, 부하 없음·health check 50회 반복 기준):

| 컨테이너 | 실측 메모리 |
|---|---|
| core-api (JVM, `-Xmx384m -XX:MaxMetaspaceSize=128m`) | 513~521MiB (50회 호출 후도 안정, 누수 없음) |
| core-api (힙 제한 없음, 참고용) | 639.6MiB |
| postgres | 83.8MiB |
| redis | 18.9MiB |
| analysis-api | 70.6MiB |
| caddy (미측정, 알려진 값) | ~20~30MiB |

힙 제한 적용 시 **idle 합계 ≈ 720MiB**. `t3.small`(2GB)에서 OS+Docker 오버헤드를 빼도
1GB 이상 여유가 남는다 — `t3.micro`(1GB)는 이 계산만으로 이미 빠듯해서 탈락, `t3.small`
이 실측 기준 안전한 최소선이다. 단, **analysis-api의 실제 몬테카를로 계산(10,000 경로
bootstrap) 하에서의 메모리 스파이크는 인증 토큰이 없어 로컬에서 측정 못 했다** — 배포
직후 실사용 흐름(로그인→계획 생성)에서 `docker stats`로 한 번 더 확인할 것(6번).

- [x] **결정: `t3.small`, JVM 힙 `-Xmx384m` 고정, 스왑 1GB 안전망 추가**

## 1. 도메인 전략 — 가장 빠른 경로

`web/vercel.ts`가 `/api/v1/*`를 Vercel의 rewrite(서버 간 프록시)로 EC2에 넘기고,
`web/src/api.ts`도 상대경로 `/api/v1${path}`로만 호출한다. 즉 **브라우저는 EC2 도메인을
직접 보지 않는다** — 쿠키(`AuthController`가 `Domain` 속성을 안 붙이는 host-only
cookie)와 CORS 모두 브라우저 기준으로는 Vercel 도메인 하나만 상대한다. 이 덕분에
`docs/배포-권장-설계안.md`가 요구하는 "같은 eTLD+1 형제 도메인"은 **필수가 아니다**.
단, Vercel의 rewrite 프록시가 EC2로 서버 간 fetch를 하려면 **EC2 쪽이 유효한
HTTPS(신뢰되는 인증서)여야** 한다.

→ 커스텀 도메인을 새로 사는 대신, **EC2 Elastic IP의 퍼블릭 DNS 이름을 그대로 쓴다.**
`ec2-<ip>.ap-northeast-2.compute.amazonaws.com` 형태의 이름이 그대로 살아있고 Let's
Encrypt 인증서 발급이 된다.

- [ ] **결정: API 도메인 = EC2 Elastic IP의 퍼블릭 DNS 이름(3번에서 확정)**

## 2. 팀원 계정 로그인 (콘솔)

1. [ ] https://console.aws.amazon.com 에서 팀원이 준 ID/PW로 로그인 (IAM 사용자 로그인이면
       계정 ID·별칭도 같이 받아야 함)
2. [ ] 오른쪽 위 리전 선택기에서 **아시아 태평양(서울) ap-northeast-2** 로 전환 — AMI ID·
       요금이 리전마다 다르므로 여기서 안 맞추면 아래 단계가 다 어긋난다
3. [ ] (선택) 우측 상단 계정 메뉴 → "청구 및 비용 관리" → "프리 티어" 페이지에서 이 계정에
       EC2(t2.micro/t3.micro 750시간) 항목이 실제로 뜨는지 확인. `t3.small` 자체는
       750시간 무료 대상이 아니라서(대상은 micro뿐) 떠도 안 떠도 소액 과금되지만
       (심사 기간 ~130시간 기준 약 $3~4), 계정이 진짜 12개월 이내인지 참고는 된다

## 3. EC2 인스턴스 생성 (콘솔)

EC2 콘솔 → **"인스턴스 시작"** 버튼. 한 페이지에서 대부분 끝난다.

1. [ ] **이름**: `odyssey-prod`
2. [ ] **애플리케이션 및 OS 이미지**: `Ubuntu Server 24.04 LTS` 선택, 아키텍처는
       **64비트(x86)** (arm64/Graviton 버전과 헷갈리지 않게 x86 확인 — 기본 로그인
       계정이 `ubuntu`가 된다, `ec2-user` 아님)
3. [ ] **인스턴스 유형**: `t3.small` (드롭다운 검색창에 직접 입력, "프리 티어 사용 가능"
       배지가 없어도 그대로 선택 — 실측으로 이미 검증됨, 0-1번 참고)
4. [ ] **키 페어**: "새 키 페어 생성" 클릭 → 이름 `odyssey-deploy`, 유형 `RSA`, 프라이빗
       키 형식 `.pem` → **키 페어 생성** → **.pem 파일이 자동 다운로드된다. 이 파일은
       두 번 다시 못 받으니 바로 안전한 위치로 옮기고 터미널에서 권한을 잠근다:**
       ```bash
       mv ~/Downloads/odyssey-deploy.pem ~/.ssh/odyssey-deploy.pem
       chmod 400 ~/.ssh/odyssey-deploy.pem
       ```
5. [ ] **네트워크 설정**: "편집" 클릭
   - VPC/서브넷: 기본값 그대로 (기본 VPC 아무 가용 영역)
   - 퍼블릭 IP 자동 할당: 활성화
   - 방화벽(보안 그룹): "보안 그룹 생성" 선택, 이름 `odyssey-deploy-sg`
   - 인바운드 규칙에서 체크박스로 추가:
     - `SSH` — 소스 유형을 **"내 IP"** 로 (콘솔이 지금 접속한 IP를 자동으로 채워줌)
     - `HTTP` — 소스 `0.0.0.0/0`
     - `HTTPS` — 소스 `0.0.0.0/0`
6. [ ] **스토리지 구성**: `20` GiB, 볼륨 유형 `gp3`
7. [ ] **고급 세부 정보** 펼치기 → 맨 아래 **"사용자 데이터"** 칸에 그대로 붙여넣기:
   ```bash
   #!/bin/bash
   apt-get update -y
   curl -fsSL https://get.docker.com -o get-docker.sh
   sh get-docker.sh
   usermod -aG docker ubuntu
   fallocate -l 1G /swapfile
   chmod 600 /swapfile
   mkswap /swapfile
   swapon /swapfile
   echo "/swapfile none swap sw 0 0" >> /etc/fstab
   ```
8. [ ] 오른쪽 요약에서 인스턴스 유형·키 페어·보안 그룹 확인 후 **"인스턴스 시작"** 클릭

### 3-1. Elastic IP 연결 (콘솔)

인스턴스가 재부팅돼도 도메인이 안 바뀌게 고정 IP를 붙인다.

1. [ ] EC2 콘솔 왼쪽 메뉴 → **네트워크 및 보안 → Elastic IP** → **"Elastic IP 주소 할당"**
       → 기본값으로 할당
2. [ ] 방금 만든 EIP 체크 → **작업 → Elastic IP 주소 연결** → 인스턴스 드롭다운에서
       `odyssey-prod` 선택 → 연결
3. [ ] EC2 → 인스턴스 → `odyssey-prod` 클릭 → 하단 세부 정보 탭에서
       **"퍼블릭 IPv4 DNS"** 값을 복사한다 (예:
       `ec2-x-x-x-x.ap-northeast-2.compute.amazonaws.com`) — 이게 오늘의 API 도메인이다.
       로컬 터미널에 이렇게 저장해두면 아래 명령들을 그대로 복붙할 수 있다:
       ```bash
       export PUBLIC_DNS=<콘솔에서 복사한 퍼블릭 IPv4 DNS>
       ```

- [ ] 인스턴스 상태가 "실행 중"이고 상태 검사 2/2 통과할 때까지 1~2분 대기(user-data가
      그 사이 docker·swap을 설치함)
- [ ] `ssh -i ~/.ssh/odyssey-deploy.pem ubuntu@$PUBLIC_DNS` 접속 확인, `docker ps`,
      `docker compose version`, `free -h`(스왑 1G 보이는지) 확인

## 4. 설정 파일 배포 — 코드 전체가 아니라 필요한 파일 5개만

이미지 안에 코드가 이미 다 있으니(core-api jar, analysis-api app) **git clone은
필요 없다.** `docker-compose.prod.yml`이 볼륨으로 마운트하는 파일만 호스트에 있으면
된다 — Postgres init용 SQL 3개, 정책 artifact JSON 1개, Caddy 설정 1개, 그리고
compose 정의 자체. 그래서 git도, PAT/배포 키도 필요 없다. 로컬(맥, `git checkout prod`
상태)에서 이 6개만 SSH로 바로 전송한다:

```bash
# 로컬에서
tar czf - docker-compose.prod.yml Caddyfile.backend \
  sql/01_schema.sql sql/02_integrity.sql sql/05_integrated_service.sql \
  data/policy/policy-artifact-calculable-approved-23.json \
  | ssh -i ~/.ssh/odyssey-deploy.pem ubuntu@$PUBLIC_DNS \
    'mkdir -p ~/odyssey && tar xzf - -C ~/odyssey'
```

- [ ] EC2에서 `ls ~/odyssey`로 6개 파일 다 왔는지 확인

이제부터는 EC2에 SSH로 들어가서(`ssh -i ~/.ssh/odyssey-deploy.pem ubuntu@$PUBLIC_DNS`,
`cd ~/odyssey`) 진행한다.

`.env.production`을 새로 만든다 — **로컬 `.env`의 개발용 시크릿을 그대로 복사하지
않는다**(`SPRING_PROFILES_ACTIVE=e2e`/`E2E_TOKEN`은 운영에 절대 넣지 않음).

```bash
POSTGRES_PASSWORD=$(openssl rand -base64 24)
REDIS_PASSWORD=$(openssl rand -base64 24)
INTERNAL_API_TOKEN=$(openssl rand -hex 32)
JWT_SECRET=$(openssl rand -hex 32)

cat > .env.production <<EOF
POSTGRES_DB=dacon
POSTGRES_USER=dacon
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
INTERNAL_API_TOKEN=${INTERNAL_API_TOKEN}
JWT_SECRET=${JWT_SECRET}
GOOGLE_CLIENT_ID=<로컬 .env에 이미 있는 실제 값을 그대로>
FINLIFE_API_KEY=<로컬 .env에 이미 있는 실제 값을 그대로>
APP_CORS_ALLOWED_ORIGIN=<8번에서 Vercel 도메인 확정 후 채움 — 그 전까지는 빈 값으로 닫힌 상태 유지>
API_ADDRESS=<PUBLIC_DNS, scheme 없이>
ACME_EMAIL=<본인 이메일>
LOG_LEVEL=INFO
EOF
chmod 600 .env.production
```

이미지를 `image:`로 대체하는 오버라이드 파일도 미리 만들어 둔다(JVM 힙 제한도 여기서):

```bash
cat > docker-compose.prod.images.yml <<'EOF'
services:
  core-api:
    image: seoungmun/odyssey:core-api
    environment:
      JAVA_TOOL_OPTIONS: "-Xmx384m -XX:MaxMetaspaceSize=128m"
  policy-import:
    image: seoungmun/odyssey:core-api
  analysis-api:
    image: seoungmun/odyssey:analysis-api
EOF
```

- [ ] `.env.production` 생성, 권한 600
- [ ] `docker-compose.prod.images.yml` 생성

## 5. 로컬에서 이미지 빌드 → Docker Hub push

Docker Hub 계정 `seoungmun` 기준. **레포는 private로 하나만 만든다**(`odyssey`) —
core-api/analysis-api를 별 레포로 나누면 무료 플랜의 private 레포 개수 제한에 걸릴 수
있어서, 태그로만 구분한다(`:core-api`, `:analysis-api`). **이 Mac은 arm64인데 EC2는
x86_64(`t3.small`)라 크로스 빌드가 필요하다** — `buildx`로 `--platform linux/amd64`를
지정한다(에뮬레이션이라 네이티브보다 느리지만 규모상 몇 분이면 끝난다).

```bash
docker login -u seoungmun   # Docker Hub 비밀번호(또는 access token) 입력
```

- [ ] hub.docker.com 접속 → **Create Repository** → 이름 `odyssey`, **Visibility: Private**
      선택 → 생성

```bash
# 로컬, git checkout prod 상태에서
docker buildx build --platform linux/amd64 -f Dockerfile.core -t seoungmun/odyssey:core-api --push .
docker buildx build --platform linux/amd64 -f analysis-api/Dockerfile -t seoungmun/odyssey:analysis-api --push analysis-api
```

- [ ] 두 `docker buildx build --push` 다 성공 (`docker inspect`로 아키텍처 확인하고
      싶으면 `docker buildx imagetools inspect seoungmun/odyssey:core-api`)

## 6. 백엔드 기동

EC2에서 먼저 Docker Hub 로그인(private 레포라 pull에도 로그인 필요):

```bash
docker login -u seoungmun
```

```bash
docker compose --env-file .env.production \
  -f docker-compose.prod.yml -f docker-compose.prod.images.yml pull
docker compose --env-file .env.production \
  -f docker-compose.prod.yml -f docker-compose.prod.images.yml up -d
docker compose -f docker-compose.prod.yml ps
```

`pull`로 Docker Hub에서 이미지를 받아오고 compose가 `image:`를 보고 그걸 그대로 쓰므로
빌드가 안 걸린다. 전부 `healthy`가 될 때까지 기다린다(Flyway 마이그레이션 때문에
core-api가 좀 걸릴 수 있음, healthcheck retries=30×10s).

```bash
curl -I https://$PUBLIC_DNS/actuator/health
docker compose -f docker-compose.prod.yml logs -f core-api
free -h
docker stats --no-stream
```

- [ ] `docker compose ps`에서 postgres/redis/analysis-api/core-api/caddy 전부 `healthy`
- [ ] `policy-import`가 `Exited (0)`로 끝났는지 확인 — 없으면 정책 데이터 0건으로 뜬다
- [ ] `curl -I https://$PUBLIC_DNS/actuator/health` → `200`, Caddy Let's Encrypt 발급 확인
- [ ] `docker stats --no-stream`으로 0-1번 실측치와 비슷한 범위인지 확인, `free -h`로
      스왑이 계속 늘기만 하지 않는지 확인
- [ ] **로그인 → 계획 생성까지 한 번 실제로 통과시켜서 analysis-api 메모리를
      `docker stats`로 다시 확인** (0-1번에서 못 잰 몬테카를로 부하 구간 — 여기서 처음
      실측됨)

## 7. Vercel 프론트 배포

로컬(맥)에서:

```bash
npm install -g vercel   # 로컬에 vercel CLI 없으면 설치
cd web
vercel link             # 팀원 프로젝트가 이미 있으면 그 프로젝트 선택, 없으면 새로 생성
vercel env add API_ORIGIN production
# 값 입력: https://<PUBLIC_DNS>   (반드시 https, trailing slash 없이 — web/vercel.ts가 그렇게 검증함)
vercel --prod
```

- [ ] Vercel 프로덕션 배포 URL 확보
- [ ] `API_ORIGIN`은 **Production 환경에만** 설정

## 8. 프론트 ↔ 백엔드 연결 마무리

Vercel 프로덕션 도메인이 확정되면 EC2로 돌아가서:

```bash
# EC2에서 .env.production 수정
sed -i 's|^APP_CORS_ALLOWED_ORIGIN=.*|APP_CORS_ALLOWED_ORIGIN=https://<Vercel 프로덕션 도메인>|' .env.production
docker compose --env-file .env.production \
  -f docker-compose.prod.yml -f docker-compose.prod.images.yml up -d core-api
```

- [ ] `APP_CORS_ALLOWED_ORIGIN`을 Vercel 프로덕션 도메인 정확히 하나로 채움 (와일드카드 금지)
- [ ] Google Cloud Console → OAuth 클라이언트(`.env`의 `GOOGLE_CLIENT_ID`) →
      **승인된 자바스크립트 원본**에 Vercel 프로덕션 도메인 추가
- [ ] 브라우저에서 Vercel 프로덕션 URL 접속 → 로그인 → 새로고침 후 세션 유지 →
      거래/목표/계획까지 한 번 실제로 눌러서 확인

## 9. 배포 직후 실시간 트러블슈팅 치트시트

| 증상 | 원인 후보 | 확인/조치 |
|---|---|---|
| Caddy가 443에서 계속 재시작 | ACME 인증서 발급 실패 — 80/443이 실제로 퍼블릭에서 안 열려있거나, DNS가 아직 EIP 연결 전 캐시된 값 | `curl -v http://$PUBLIC_DNS` 먼저 되는지, SG 80/443 inbound 확인, `docker compose logs caddy` |
| `core-api` healthcheck 계속 실패 | Flyway 마이그레이션 실패, `:?` 환경변수 미설정, 또는 `-Xmx384m`이 너무 빡빡해서 OOM | `docker compose logs core-api`, 그래도 죽으면 `-Xmx448m`로 살짝 올려보기 |
| `exec format error` / 컨테이너가 즉시 죽음 | amd64/arm64 아키텍처 불일치(`--platform linux/amd64` 빠뜨림) | `docker buildx imagetools inspect seoungmun/odyssey:core-api`로 아키텍처가 `linux/amd64`인지 확인 |
| `pull` 후에도 compose가 빌드하려 함 | `docker-compose.prod.images.yml`을 `-f`에 안 넣었거나 이미지 태그가 안 맞음 | `docker compose -f docker-compose.prod.yml -f docker-compose.prod.images.yml config`로 실제 `image:` 값 확인 |
| EC2에서 `docker compose pull` 시 403/unauthorized | private 레포인데 EC2에서 `docker login` 안 함, 또는 세션 만료 | EC2에서 `docker login -u seoungmun` 재실행 |
| 브라우저 로그인 후 새로고침하면 로그아웃됨 | `APP_CORS_ALLOWED_ORIGIN`이 Vercel 도메인과 정확히 안 맞음, 쿠키가 `Secure`인데 http로 접근 중 | devtools Network 탭 `/api/v1/auth/*` 응답의 `Set-Cookie` 유무, CORS 에러 문구 |
| Vercel에서 API 호출이 502/504 | `API_ORIGIN`이 EC2 도메인과 다르거나, Caddy가 아직 안 뜸, 인증서 무효 | `vercel env ls`로 값 재확인, EC2에서 `curl -I https://$PUBLIC_DNS/actuator/health` |
| Google 로그인 시도 시 401 | Vercel 도메인이 Google Cloud Console의 승인된 origin에 없음 | 콘솔에서 origin 추가 후 몇 분 대기 |
| 적금/정책 데이터가 0건 | `policy-import` 미실행, FSS API timeout(45s로 이미 고정됨) | `docker compose logs policy-import`, `docker compose logs core-api \| grep -i finlife` |
| `free -h`에서 스왑이 계속 100%로 붙어있고 응답이 느림 | `t3.small` 2GB도 부족한 실사용 트래픽(특히 analysis-api 몬테카를로 동시 요청) | `t3.medium`(4GB)으로 인스턴스 타입 변경(정지→modify→시작), 이미지는 다시 `docker compose pull`만 하면 됨 |

**코드를 고쳐서 다시 올릴 때**(오늘 실시간 대응 중 반복하게 될 절차):

```bash
# 로컬에서, 고친 서비스만
docker buildx build --platform linux/amd64 -f Dockerfile.core -t seoungmun/odyssey:core-api --push .

# EC2에서
docker compose --env-file .env.production \
  -f docker-compose.prod.yml -f docker-compose.prod.images.yml pull core-api
docker compose --env-file .env.production \
  -f docker-compose.prod.yml -f docker-compose.prod.images.yml up -d core-api
```

## 10. 마감 전 최종 점검

- [ ] `docker compose -f docker-compose.prod.yml ps` 전부 healthy 상태로 캡처해두기
- [ ] Vercel 프로덕션 URL을 시크릿 브라우징 창에서 처음부터 로그인 → 온보딩 →
      대시보드까지 한 번 통과
- [ ] `.env.production`이 git에 커밋되지 않았는지 확인
- [ ] 팀원에게 최종 Vercel 프로덕션 URL과 EC2 API 도메인 공유
- [ ] **2026-09-07 11:00 KST 전까지** 위 항목 전부 완료 — 그 이후로는 심사 인스턴스
      배포·변경 금지
