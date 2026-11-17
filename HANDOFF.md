# 작업 인계 (HANDOFF) — spring 브랜치

마지막 갱신: 2026-11-17. 다음 세션은 이 파일 → `AGENTS.md` → `README.md` 순서로 읽고 시작한다.
일정: **최종 발표·시연 2026-11-24**.

## 1. 현재 상태

- `spring` 브랜치 = Java Spring Boot 판. `main` 은 Django 판이며 두 브랜치는 합치지 않는다 (2026-11-17 결정).
- 2026-11-17: Django 판 기능을 Spring 으로 모두 이식 (PR #20~#27, 평가 측정 명령만 제외)
- 2026-11-17 리팩토링 (`refactor/spring-design-alignment`)
  - Django 판 코드 삭제, Spring 프로젝트를 저장소 루트로, 공용 정적 파일은 `src/main/resources/static/`
  - 패키지·클래스·메소드 이름을 설계서(계획서·요구분석서·설계서)에 맞춤. 대응표는 `AGENTS.md` "설계와 구현의 대응"
  - Data Access 서브시스템(`DataAccessAPI`·`DataAccessManager`·Repository 3개)을 실제로 두고, 업무 클래스는 이것만 쓴다
  - 화면 클래스는 설계의 View 이름으로 `ui` 패키지에 (URL 은 그대로)
  - 테스트 155개 통과, 데모 데이터로 띄워 고객·직원 주요 화면·신청·입고·음성 검색 확인
- 배포: 서버(3-35-38-138.sslip.io)는 아직 Django 판. Spring 판은 `deploy/DEPLOY.md` 로 `/srv/mwt-spring` 에 따로 설치 가능

## 2. 환경 메모 (비밀값은 각 PC·서버의 `.env`에만)

- `.env` 형식은 `.env.example`. 문자 설정 `SMS_CLIENT=Console|FlakyDemo|Solapi`
- 솔라피 API Key 는 허용 IP 제한이 있다. 다른 PC 는 `SMS_CLIENT` 를 빼서 가짜 발송(로그)으로 개발
- 개발 DB(H2)는 `data/`. 구조가 바뀌어 꼬이면 서버를 끄고 `data/` 삭제 → 다시 `--seed-demo`

## 3. 남은 일

1. 문서와 구현 대조 (팀)
2. (선택) Spring 판 서버 배포 후 PostgreSQL 에서 동시 신청 확인
3. (선택) 평가 항목 측정 명령 이식
4. **발표(11/24) 후 AWS 정리**: 인스턴스 종료, 탄력적 IP 해제, 보안 그룹·키 삭제
