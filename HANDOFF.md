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
- 2026-11-17 배포: 사이트 **https://3-35-38-138.sslip.io/** 를 Spring 판으로 전환 (`deploy/DEPLOY.md` 절차, `SKIP_TESTS=1`)
  - 서버 `/srv/mwt-spring` (`spring` 브랜치), 서비스 `mwt-spring`, DB `mwt_spring`, Nginx 사이트 `mwt-spring` (메인 주소 인증서 사용)
  - `.env` 는 Django 판 값(솔라피 실제 발송 + `SMS_ALLOWED_NUMBERS`)을 복사한 별도 파일. 직원 가입 코드는 서버 `.env` 의 `STAFF_SIGNUP_CODE`
  - 데모 데이터 넣음 (`staff1`·`regular1`·`customer2`·`newbie`, 비밀번호 `demo1234!`)
  - Django 판은 중지만 했다: 서비스 `mwt` 중지·자동 시작 해제, Nginx `sites-enabled/mwt` 링크 제거.
    `/srv/mwt`·DB `mwt`·`/etc/nginx/sites-available/mwt` 는 남겨 둠 (되돌리기: `mwt-spring` 링크 제거 → `mwt` 링크 복구 → `sudo systemctl enable --now mwt` → nginx reload)
  - 접속: `ssh -i ~/.ssh/mwt-key.pem ubuntu@3.35.38.138` (키는 정용범 PC 에만, 보안 그룹 SSH 는 정용범 집 IP 만 허용)
  - 코드 갱신: 서버에서 `bash /srv/mwt-spring/deploy/update.sh`
  - 백업(계획서 위험 분석): `mwt-backup.timer` 가 매일 04:00 KST 에 DB 덤프 + `.env`·사진을 `/var/backups/mwt` 에 14일 보관. 복원 시험 완료.
    전환 직전 Django DB 도 `django-mwt-before-spring-20261117.dump` 로 한 번 남김. 같은 디스크라 인스턴스를 잃으면 함께 사라진다

## 2. 환경 메모 (비밀값은 각 PC·서버의 `.env`에만)

- `.env` 형식은 `.env.example`. 문자 설정 `SMS_CLIENT=Console|FlakyDemo|Solapi`
- 솔라피 API Key 는 허용 IP 제한이 있다. 다른 PC 는 `SMS_CLIENT` 를 빼서 가짜 발송(로그)으로 개발
- 개발 DB(H2)는 `data/`. 구조가 바뀌어 꼬이면 서버를 끄고 `data/` 삭제 → 다시 `--seed-demo`

## 3. 남은 일

1. 문서와 구현 대조 (팀)
2. (선택) 서버 PostgreSQL 에서 동시 신청(정원·재고) 확인
3. (선택) 평가 항목 측정 명령 이식
4. **발표(11/24) 후 AWS 정리**: 인스턴스 종료, 탄력적 IP 해제, 보안 그룹·키 삭제
