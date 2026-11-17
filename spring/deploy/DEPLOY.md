# Spring Boot 판 서버 배포 안내 (AWS EC2 · Ubuntu 24.04 · Nginx · Java 21 · PostgreSQL 18)

Django 판 안내(`deploy/DEPLOY.md`)와 같은 방식이다. 완료하면 `https://spring.<공인IP를-로>.sslip.io` 주소로 접속된다
(예: IP `3.35.10.20` → `https://spring.3-35-10-20.sslip.io`). sslip.io 는 앞에 붙은 이름과 상관없이 IP 로 연결해 주므로,
같은 서버에서 Django 판(`3-35-10-20.sslip.io`)과 Spring 판을 나란히 띄울 수 있다. 음성 선택(마이크)은 HTTPS 에서만 동작한다.

| | Django 판 | Spring 판 |
|---|---|---|
| 설치·갱신 | `deploy/setup.sh` · `deploy/update.sh` | `spring/deploy/setup.sh` · `spring/deploy/update.sh` |
| systemd 서비스 | `mwt` (Gunicorn, 유닉스 소켓) | `mwt-spring` (`java -jar spring/run/mwt.jar --spring.profiles.active=prod`, 127.0.0.1:8080) |
| Nginx 사이트 | `/etc/nginx/sites-available/mwt` | `/etc/nginx/sites-available/mwt-spring` |
| 기본 주소 | `<IP>.sslip.io` | `spring.<IP>.sslip.io` |
| DB (PostgreSQL) | `mwt` (Django migrate) | **`mwt_spring`** (Flyway 가 시작할 때 표를 만든다) |
| 업로드 사진 | `/srv/mwt/media/` | `/srv/mwt/spring/media/` (`mwt.media-root`) |
| 비밀값 | 저장소 루트 `/srv/mwt/.env` | **같은 파일**을 그대로 읽는다 (`application.yml` 의 `optional:file:../.env`) |

DB 를 따로 두는 이유: 두 판은 표 이름이 비슷하지만 구조와 이력 관리(Django migrations ↔ Flyway)가 달라 한 DB 에 섞으면
서로의 표를 망가뜨린다. `.env` 의 `POSTGRES_DB` 는 Django 판 값으로 두고, Spring 서비스는 systemd 의
`Environment=POSTGRES_DB=mwt_spring` 으로 덮어쓴다 (환경변수가 `.env` 보다 우선). DB 계정(`mwt`)·비밀번호는 같이 쓴다.

## 1~3. EC2 만들기 · 접속 · 배포 키

Django 판 안내 `deploy/DEPLOY.md` 의 1~3장과 같다 (Ubuntu 24.04, 보안 그룹 22·80·443, 배포 키 등록).
이미 Django 판을 설치한 서버라면 이 단계는 건너뛴다.

> 메모리: Spring 판만 쓰면 t3.small(2GB)로 충분하다. Django 판과 함께 띄우면 빌드 중 메모리가 빠듯할 수 있으니
> t3.small 이상을 권장한다 (스크립트가 스왑 1GB 를 추가하고, JVM 힙은 최대 512MB 로 제한).

## 4. 설치 (한 번 실행, 약 5~10분)

Spring 판은 **`spring` 브랜치**를 받아 빌드한다 (`main` 은 Django 판 배포용).

**A. Spring 판만 쓰는 서버** (저장소가 아직 없을 때):
```bash
sudo mkdir -p /srv/mwt && sudo chown ubuntu:ubuntu /srv/mwt
git clone -b spring git@github.com:yongbeom-jeong/mister-world-tour.git /srv/mwt
bash /srv/mwt/spring/deploy/setup.sh
```

**B. Django 판(`/srv/mwt`, main)이 이미 있는 서버**: Django 판 저장소의 브랜치는 건드리지 않고 Spring 판을 따로 받는다.
`.env` 는 Django 판 파일을 링크해서 같이 쓴다 (스크립트는 링크를 따라가며 값을 고친다).
```bash
sudo mkdir -p /srv/mwt-spring && sudo chown ubuntu:ubuntu /srv/mwt-spring
git clone -b spring git@github.com:yongbeom-jeong/mister-world-tour.git /srv/mwt-spring
ln -s /srv/mwt/.env /srv/mwt-spring/.env
bash /srv/mwt-spring/spring/deploy/setup.sh
```
B 의 경우 아래 명령의 `/srv/mwt/spring/...` 를 `/srv/mwt-spring/spring/...` 로 바꿔 읽는다.

하는 일: Java 21(`openjdk-21-jdk-headless`)·PostgreSQL 18·Nginx 설치 → `.env` 확인(없으면 새로 만듦) → DB `mwt_spring` 생성
→ `mvnw package`(테스트 포함, 메모리 H2) → Nginx(`/media/` 는 직접 제공, 나머지는 Spring 으로 프록시)
→ HTTPS 인증서 → `mwt-spring` 서비스 → 데모 데이터(`--seed-demo`).
끝나면 접속 주소, 데모 계정, 직원 가입 코드가 출력된다.

바꿀 수 있는 값 (예: `SKIP_TESTS=1 bash /srv/mwt/spring/deploy/setup.sh`)

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `HOST` | `spring.<IP>.sslip.io` | 사용할 도메인 |
| `PORT` | `8080` | Spring 이 듣는 포트 (127.0.0.1 에만 열림) |
| `DB_NAME` | `mwt_spring` | Spring 판 DB 이름 |
| `JAVA_OPTS` | `-Xms128m -Xmx512m` | JVM 옵션 |
| `SKIP_HTTPS` / `SKIP_SEED` / `SKIP_TESTS` | | `1` 이면 인증서 / 데모 데이터 / 빌드 테스트 생략 |

참고
- HTTPS 인증서 발급에 실패해 HTTP 로만 쓰면 서비스에 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` 를 넣는다
  (`application-prod.yml` 은 보안 쿠키라 HTTP 에서는 로그인이 유지되지 않음). 인증서를 받으면 `setup.sh` 를 다시 실행한다.
- Nginx 가 `X-Forwarded-Proto`·`X-Forwarded-For` 를 넘기고, `application-prod.yml` 의 `forward-headers-strategy: native` 가
  이를 받아 https 주소·리디렉트를 올바르게 만든다.
- CSS·JS·기본 사진(저장소 `static/`)은 jar 에 들어 있어 Spring 이 제공한다. Nginx 는 직원이 올린 사진(`/media/`)만 직접 제공한다.
- `SMS_CLIENT` 는 Django 판 값(`apps.sms.services.ConsoleSMSClient` 등)도 그대로 알아듣는다.

## 5. 운영

| 하고 싶은 것 | 명령 (서버에서) |
|---|---|
| 코드 갱신 (git push 후) | `bash /srv/mwt/spring/deploy/update.sh` (빌드 테스트 생략, `RUN_TESTS=1` 이면 실행) |
| PR 병합 후 `spring` 브랜치로 전환 | `BRANCH=spring bash /srv/mwt/spring/deploy/update.sh` |
| 로그 보기 (가짜 문자 발송 포함) | `sudo journalctl -u mwt-spring -f` |
| 재시작 | `sudo systemctl restart mwt-spring` |
| 완료 여행 이력 일괄 반영 | `bash /srv/mwt/spring/deploy/run.sh --complete-trips` |
| 문자 시험 발송 | `bash /srv/mwt/spring/deploy/run.sh --send-test-sms=010-1234-5678` |
| 데모 데이터 다시 넣기 (중복 없음) | `SKIP_TESTS=1 bash /srv/mwt/spring/deploy/setup.sh` |
| 서버에서 테스트 | `cd /srv/mwt/spring && ./mvnw test` (메모리 H2. 동시성 확인은 PostgreSQL 에서 화면으로) |
| DB 직접 보기 | `sudo -u postgres psql mwt_spring` |
| 비밀값 확인·변경 | `nano /srv/mwt/.env` 후 `sudo systemctl restart mwt-spring` (Django 판도 쓰면 `mwt` 도 재시작) |

## 문제 해결

| 증상 | 확인 |
|---|---|
| 502 Bad Gateway | 시작에 20~30초 걸린다. 계속되면 `sudo journalctl -u mwt-spring -n 80` |
| 시작 실패: Flyway 오류 | 서비스 DB 가 `mwt_spring` 인지 `systemctl show -p Environment mwt-spring` 로 확인 (Django DB 를 가리키면 안 됨) |
| 로그인해도 바로 풀림 | HTTP 로 접속 중인데 보안 쿠키가 켜진 경우 → `setup.sh` 다시 실행 (인증서 결과에 맞춰 설정) |
| 빌드 중 멈춤·메모리 부족 | `free -h` 확인. Django 판을 잠시 멈추고(`sudo systemctl stop mwt`) 다시 실행 |
| 사진 업로드 후 사진이 안 보임 | `ls -ld /srv/mwt /srv/mwt/spring /srv/mwt/spring/media` 에 다른 사용자 실행(x) 권한이 있는지 |
| 인증서 발급 실패 | 보안 그룹에 80·443 이 열려 있는지 확인 후 `setup.sh` 다시 실행 |
| IP 가 바뀜 (탄력적 IP 없이 재시작) | `setup.sh` 다시 실행 → 새 주소로 설정·인증서 발급 |
