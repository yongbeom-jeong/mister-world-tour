# 서버 배포 안내 (AWS EC2 · Ubuntu 24.04 · Nginx · Gunicorn · PostgreSQL 18)

계획서 5장 개발 환경 기준. 완료하면 `https://<공인IP를-로>.sslip.io` 주소로 접속된다
(예: IP `3.35.10.20` → `https://3-35-10-20.sslip.io`). sslip.io 는 IP 를 그대로 이름으로 바꿔 주는 무료 주소이고,
여기에 Let's Encrypt 무료 인증서를 붙인다. 음성 선택(마이크)은 HTTPS 에서만 동작한다.

## 1. EC2 만들기 (AWS 콘솔, 약 5분)

1. https://console.aws.amazon.com 로그인 → 오른쪽 위 리전을 **아시아 태평양(서울) ap-northeast-2** 로 선택
2. **EC2 → 인스턴스 시작(Launch instance)**
   | 항목 | 값 |
   |---|---|
   | 이름 | `mwt-server` |
   | AMI | **Ubuntu Server 24.04 LTS** (64비트 x86) |
   | 인스턴스 유형 | **t3.small** (메모리 2GB. 비용을 줄이려면 t3.micro 도 가능 — 스크립트가 스왑을 추가함) |
   | 키 페어 | **새 키 페어 생성** → 이름 `mwt-key`, 유형 RSA, 형식 `.pem` → 내려받은 파일을 `C:\Users\<사용자>\.ssh\mwt-key.pem` 으로 옮김 |
   | 네트워크 설정 | **SSH 트래픽 허용: 내 IP**, **인터넷에서 HTTPS 트래픽 허용**, **인터넷에서 HTTP 트래픽 허용** 모두 체크 |
   | 스토리지 | 20 GiB gp3 |
3. **인스턴스 시작** → 목록에서 상태가 "실행 중"이 될 때까지 기다림
4. (권장) **EC2 → 탄력적 IP → 탄력적 IP 주소 할당 → 작업: 연결** 로 `mwt-server` 에 연결.
   인스턴스를 껐다 켜도 IP(=사이트 주소)가 바뀌지 않는다.
5. 인스턴스의 **퍼블릭 IPv4 주소**를 메모

> 비용(대략): t3.small 기준 공인 IPv4·디스크 포함 월 3만 원 안팎 (t3.micro 면 그 절반 정도). 정확한 금액은 AWS 요금표 확인. 발표가 끝나면 인스턴스를 **중지**하거나 **종료**하고,
> 탄력적 IP 를 썼다면 **릴리스**해야 요금이 멈춘다.

## 2. 내 PC 에서 접속 (PowerShell)

```powershell
$key = "$HOME\.ssh\mwt-key.pem"
icacls $key /inheritance:r /grant:r "$($env:USERNAME):R"   # 키 파일 권한 (처음 한 번)
ssh -i $key ubuntu@<퍼블릭IP>
```
"Are you sure you want to continue connecting" 가 나오면 `yes`.

## 3. 서버가 비공개 저장소를 받을 수 있게 하기 (배포 키, 처음 한 번)

서버(ssh 접속한 창)에서:
```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/mwt_deploy -C mwt-server
printf 'Host github.com\n  IdentityFile ~/.ssh/mwt_deploy\n  StrictHostKeyChecking accept-new\n' >> ~/.ssh/config
cat ~/.ssh/mwt_deploy.pub
```
마지막 줄에 나온 `ssh-ed25519 ...` 한 줄을 복사해서
GitHub 저장소 → **Settings → Deploy keys → Add deploy key** 에 붙여 넣는다 (제목 `mwt-server`, **Allow write access 는 체크하지 않음**).

## 4. 설치 (한 번 실행, 약 5~10분)

```bash
sudo mkdir -p /srv/mwt && sudo chown ubuntu:ubuntu /srv/mwt
git clone -b feat/django-skeleton git@github.com:yongbeom-jeong/mister-world-tour.git /srv/mwt
bash /srv/mwt/deploy/setup.sh
```
끝나면 접속 주소, 데모 계정, 직원 가입 코드가 출력된다.
PR 이 main 에 병합된 뒤에는 `-b feat/django-skeleton` 대신 기본 브랜치(main)를 쓰면 된다.

## 5. 운영

| 하고 싶은 것 | 명령 (서버에서) |
|---|---|
| 코드 갱신 (git push 후) | `bash /srv/mwt/deploy/update.sh` |
| PR 병합 후 main 으로 전환 | `BRANCH=main bash /srv/mwt/deploy/update.sh` |
| 로그 보기 (가짜 문자 발송 포함) | `sudo journalctl -u mwt -f` |
| 재시작 | `sudo systemctl restart mwt` |
| 서버에서 테스트 (PostgreSQL) | `cd /srv/mwt && .venv/bin/python manage.py test --noinput` |
| 관리자(/admin) 계정 만들기 | `cd /srv/mwt && .venv/bin/python manage.py createsuperuser` |
| 비밀값 확인·변경 | `nano /srv/mwt/.env` 후 `sudo systemctl restart mwt` |

## 문제 해결

| 증상 | 확인 |
|---|---|
| ssh 접속이 안 됨 | 보안 그룹의 SSH 가 "내 IP" 인데 집·학교 등 IP 가 바뀐 경우 → 보안 그룹 인바운드 규칙에서 내 IP 로 다시 수정 |
| 인증서 발급 실패 | 보안 그룹에 80·443 이 열려 있는지 확인 후 `bash /srv/mwt/deploy/setup.sh` 다시 실행 |
| 502 Bad Gateway | `sudo journalctl -u mwt -n 50` 로 오류 확인 |
| IP 가 바뀜 (탄력적 IP 없이 재시작) | `bash /srv/mwt/deploy/setup.sh` 다시 실행 → 새 주소로 설정·인증서 발급 |
