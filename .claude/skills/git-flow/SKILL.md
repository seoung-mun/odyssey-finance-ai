---
name: git-flow
description: 이 프로젝트에서 브랜치를 만들거나, 커밋하거나, PR을 올릴 때 사용. git flow 브랜치 전략과 Conventional Commits 규칙, 커밋 분리 기준을 담고 있다. 커밋 메시지 작성, 브랜치 이름 결정, PR 본문 작성, 머지 방식 선택에 사용한다.
---

# git flow — 브랜치·커밋·PR

훅이 형식을 강제한다(`.githooks/`). 이 문서는 **훅이 잡아주지 못하는 판단**
— 무엇을 한 커밋으로 묶을지, 제목에 무엇을 쓸지 — 을 다룬다.

## 브랜치

```
main        배포 가능 상태만. 제출 시점은 태그로 고정 (v1.0-본선제출)
develop     통합 브랜치. feature를 여기서 따고 여기로 머지
feature/*   기능 단위 작업
release/*   제출 직전 안정화
hotfix/*    main 긴급 수정
```

이름은 `feature/<서비스>-<내용>` 소문자·하이픈:

```
feature/analysis-montecarlo-block-bootstrap
feature/core-plan-crud
feature/web-fan-chart
```

`main`/`develop` 직접 푸시는 `pre-push` 훅이 막는다. 항상 PR로.

## 커밋 메시지

```
<type>(<scope>): <제목, 한국어, 명령형, 마침표 없음, 72자 이내>

<본문: 왜 이렇게 바꿨는지. 무엇을 바꿨는지는 diff가 말한다>
```

**type**: `feat` `fix` `refactor` `test` `docs` `chore` `perf` `build` `revert`
**scope**: `core` `analysis` `engine` `web` `infra` `docs`

좋은 예 — 본문이 **왜**를 설명한다:

```
feat(engine): 블록 부트스트랩으로 몬테카를로 리샘플링 교체

IID 부트스트랩은 월 간 자기상관을 버려서 소비의 계절성이 사라짐.
연속 2~3개월 블록 단위로 리샘플링해 관측치 12~24개월의 부족한
표본 문제도 조합 다양성으로 완화. 기획서 5-5 반영.
```

나쁜 예:

```
feat(engine): 코드 수정          ← 무엇을 했는지 알 수 없음
fix(engine): 버그 수정함.        ← 어떤 버그인지 없음, 마침표
feat(engine): 몬테카를로 수정 + API 추가 + 린트   ← 한 커밋에 세 가지
```

## 커밋 분리 기준

**한 커밋 = 한 가지 변경.** 되돌릴 때 딸려오는 게 없어야 한다.

- 리팩터링과 기능 추가를 섞지 않는다. 리뷰어가 진짜 변경을 못 찾는다.
- 포맷팅만 바꾼 커밋은 따로 분리한다(`chore`).
- 기획서 변경(`docs`)은 그 결정을 구현한 커밋과 분리해도 되지만,
  본문에서 서로 참조한다.

커밋 메시지는 리뷰어와 **나중의 면접관**이 읽는 문서다. 그 기준으로 쓴다.

## PR

제목은 커밋 제목과 같은 규칙. 본문에 담을 것:

- **왜** 이 변경이 필요한지 (기획서 절 번호 참조)
- 검증 방법 — 돌린 셀프체크/테스트와 그 결과
- 미확정으로 남긴 것, `ponytail:` 로 표시한 의도적 shortcut

리뷰 1명 이상. `develop`으로 머지.

## 훅이 막았을 때

형식 오류는 훅 메시지가 이유를 알려준다. 훅을 우회하지 말 것 —
`--no-verify` 는 히스토리를 깨뜨린다. 규칙 자체가 문제라면 훅을 고치고
그 변경도 커밋한다(`chore(infra)`).

훅이 안 도는 것 같으면 설정부터 확인:

```bash
git config core.hooksPath .githooks
```
