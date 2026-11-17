# 서버 배포 안내 (AWS EC2 · Ubuntu 24.04 · Nginx · Java 21 · PostgreSQL 18)

`spring` 브랜치를 서버에 받아 jar 로 빌드하고 systemd 서비스 `mwt-spring` 으로 띄운다.
완료하면 `https://spring.<공인IP를-로>.sslip.io` 주소로 접속된다 (예: IP `3.35.10.20` → `https://spring.3-35-10-20.sslip.io`).
음성 선택(마이크)은 HTTPS 에서만 동작한다.

| 항목 | 값 |
|---|---|
| 설치·갱신 | `deploy/setup.sh` · `deploy/update.sh` (명령 실행은 `deploy/run.sh`) |
| systemd 서비스 | `mwt-spring` (`java -jar run/mwt.jar --spring.profiles.active=prod`, 127.0.0.1:8080) |
| Nginx 사이트 | `/etc/nginx/sites-available/mwt-spring` (`/media/` 는 직접 제공, 나머지는 Spring 으로 프록시) |
| DB (PostgreSQL) | `mwt_spring` (Flyway 가 시작할 때 표를 만든다) |
| 업로드 사진 | `<저장소>/media/` (`mwt.media-root`) |
| 비밀값 | 저장소 루트 `.env` |

`main` 브랜치의 Django 판(`/srv/mwt`, 서비스 `mwt`, DB `mwt`)과 같은 서버에 나란히 띄울 수 있다. 주소·서비스·DB 이름이 모두 다르다.
Spring 서비스는 systemd 의 `Environment=POSTGRES_DB=mwt_spring` 으로 DB 이름을 정한다 (환경변수가 `.env` 보다 우선).

## 1. EC2 준비

Ubuntu 24.04, 보안 그룹 22·80·443, GitHub 배포 키 등록. Django 판을 이미 설치한 서버라면 건너뛴다.

> 메모리: t3.small(2GB)이면 충분하다. 스크립트가 스왑 1GB 를 추가하고 JVM 힙은 최대 512MB 로 제한한다.

## 2. 설치 (한 번 실행, 약 5~10분)

```bash
sudo mkdir -p /srv/mwt-spring && sudo chown ubuntu:ubuntu /srv/mwt-spring
git clone -b spring git@github.com:yongbeom-jeong/mister-world-tour.git /srv/mwt-spring
# Django 판과 같은 비밀값을 쓰려면: ln -s /srv/mwt/.env /srv/mwt-spring/.env
bash /srv/mwt-spring/deploy/setup.sh
```

하는 일: Java 21·PostgreSQL 18·Nginx 설치 → `.env` 확인(없으면 `.env.example` 로 만들고 비밀값 무작위 생성) → DB `mwt_spring` 생성
→ `mvnw package`(테스트 포함, 메모리 H2) → Nginx → HTTPS 인증서 → `mwt-spring` 서비스 → 데모 데이터(`--seed-demo`) → 매일 백업 타이머(`mwt-backup.timer`).
끝나면 접속 주소, 데모 계정, 직원 가입 코드가 출력된다.

바꿀 수 있는 값 (예: `SKIP_TESTS=1 bash /srv/mwt-spring/deploy/setup.sh`)

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `HOST` | `spring.<IP>.sslip.io` | 사용할 도메인 |
| `PORT` | `8080` | Spring 이 듣는 포트 (127.0.0.1 에만 열림) |
| `DB_NAME` | `mwt_spring` | DB 이름 |
| `JAVA_OPTS` | `-Xms128m -Xmx512m` | JVM 옵션 |
| `SKIP_HTTPS` / `SKIP_SEED` / `SKIP_TESTS` | | `1` 이면 인증서 / 데모 데이터 / 빌드 테스트 생략 |

참고
- HTTPS 인증서 발급에 실패해 HTTP 로만 쓰면 서비스에 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` 를 넣는다
  (`application-prod.yml` 은 보안 쿠키라 HTTP 에서는 로그인이 유지되지 않음). 인증서를 받으면 `setup.sh` 를 다시 실행한다.
- Nginx 가 `X-Forwarded-Proto`·`X-Forwarded-For` 를 넘기고, `application-prod.yml` 의 `forward-headers-strategy: native` 가
  이를 받아 https 주소·리디렉트를 올바르게 만든다.
- CSS·JS·기본 사진(`src/main/resources/static/`)은 jar 에 들어 있어 Spring 이 제공한다.

## 3. 운영

| 하고 싶은 것 | 명령 (서버에서) |
|---|---|
| 코드 갱신 (git push 후) | `bash /srv/mwt-spring/deploy/update.sh` (빌드 테스트 생략, `RUN_TESTS=1` 이면 실행) |
| 로그 보기 (가짜 문자 발송 포함) | `sudo journalctl -u mwt-spring -f` |
| 재시작 | `sudo systemctl restart mwt-spring` |
| 완료 여행 이력 일괄 반영 | `bash /srv/mwt-spring/deploy/run.sh --complete-trips` |
| 문자 시험 발송 | `bash /srv/mwt-spring/deploy/run.sh --send-test-sms=010-1234-5678` |
| 데모 데이터 다시 넣기 (중복 없음) | `SKIP_TESTS=1 bash /srv/mwt-spring/deploy/setup.sh` |
| 서버에서 테스트 | `cd /srv/mwt-spring && ./mvnw test` (메모리 H2) |
| DB 직접 보기 | `sudo -u postgres psql mwt_spring` |
| 백업 (매일 04:00 KST 자동, 14일 보관) | `sudo systemctl start mwt-backup` 로 바로 실행, 파일은 `sudo ls /var/backups/mwt` (DB 덤프 + `.env`·사진 묶음) |
| 백업에서 DB 복원 | `sudo systemctl stop mwt-spring` → `sudo cat /var/backups/mwt/db-mwt_spring-<시각>.dump \| sudo -u postgres pg_restore -c -d mwt_spring` → 서비스 시작 |
| 비밀값 확인·변경 | `nano /srv/mwt-spring/.env` 후 `sudo systemctl restart mwt-spring` |

## 문제 해결

| 증상 | 확인 |
|---|---|
| 502 Bad Gateway | 시작에 20~30초 걸린다. 계속되면 `sudo journalctl -u mwt-spring -n 80` |
| 시작 실패: Flyway 오류 | 서비스 DB 가 `mwt_spring` 인지 `systemctl show -p Environment mwt-spring` 로 확인 (Django DB 를 가리키면 안 됨) |
| 로그인해도 바로 풀림 | HTTP 로 접속 중인데 보안 쿠키가 켜진 경우 → `setup.sh` 다시 실행 (인증서 결과에 맞춰 설정) |
| 빌드 중 멈춤·메모리 부족 | `free -h` 확인. 같은 서버의 Django 판을 잠시 멈추고(`sudo systemctl stop mwt`) 다시 실행 |
| 사진 업로드 후 사진이 안 보임 | `ls -ld /srv/mwt-spring /srv/mwt-spring/media` 에 다른 사용자 실행(x) 권한이 있는지 |
| 인증서 발급 실패 | 보안 그룹에 80·443 이 열려 있는지 확인 후 `setup.sh` 다시 실행 |
| IP 가 바뀜 (탄력적 IP 없이 재시작) | `setup.sh` 다시 실행 → 새 주소로 설정·인증서 발급 |
