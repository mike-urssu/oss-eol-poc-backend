# 운영 배포 (Jenkins)

`main` 브랜치를 Jenkins가 빌드해 운영 서버 1대에 Docker로 배포하고, Traefik을 통해 `https://oss-poc.jjoon.cloud`로 서비스한다. 비밀값(DB 비밀번호, Anthropic API 키)은 Jenkins를 거치지 않고 운영 서버의 `app.env`에만 둔다.

```
GitHub ──webhook──▶ Jenkins: Test → Build & push image(Jib) → Approve(수동) → Deploy(SSH) → Verify
                                         │                              │
                                  ghcr.io ◀──────────────────── pull ── 운영 서버 (docker compose)

 운영 서버:  Traefik ──(proxy 네트워크)──▶ app ──(postgresql 네트워크)──▶ PostgreSQL 컨테이너
             oss-poc.jjoon.cloud
```

| 파일 | 역할 |
|------|------|
| `Jenkinsfile` | 파이프라인. 상단 `environment`의 `TODO` 값을 환경에 맞게 바꾼다. |
| `build.gradle.kts`의 `jib` | `eclipse-temurin:21-jre` 기반 이미지를 Docker 데몬 없이 만들어 push한다. `nobody`(65534) 사용자로 실행한다. 대상 플랫폼은 linux/amd64. |
| `deploy/docker-compose.yml` | 운영 서버에서 실행할 서비스 정의. 배포 때마다 Jenkins가 복사한다. |
| `deploy/app.env.example` | 운영 서버 `app.env`의 예시. |

## 처음 한 번 설정

### 1. 운영 DB (postgresql 네트워크의 기존 PostgreSQL)
- 데이터베이스와 계정을 만든다. 계정에 스키마 생성 권한이 있어야 한다(기동 시 Flyway가 테이블과 시드를 만든다).
  ```sql
  CREATE USER eol WITH PASSWORD '...';
  CREATE DATABASE eol OWNER eol;
  ```
- `app.env`의 `DB_URL` 호스트는 `postgresql` 네트워크 안의 PostgreSQL 컨테이너 이름이다.

### 2. 운영 서버
- Docker Engine과 Docker Compose 플러그인, 그리고 외부 네트워크 `proxy`(Traefik)와 `postgresql`이 이미 있어야 한다.
- `deploy/docker-compose.yml`의 Traefik 라벨 중 entrypoint(`websecure`)와 인증서 resolver(`letsencrypt`) 이름을 서버의 Traefik 설정에 맞춘다.
- DNS에서 `oss-poc.jjoon.cloud`가 운영 서버를 가리키게 한다.
- 배포 계정(예: `deploy`)을 만들고 `docker` 그룹에 넣는다.
- 배포 디렉터리를 만들고 `app.env`를 작성한다.
  ```bash
  sudo mkdir -p /opt/eol-poc && sudo chown deploy: /opt/eol-poc
  cp app.env.example /opt/eol-poc/app.env   # 값 채우기
  chmod 600 /opt/eol-poc/app.env
  ```
- 배포 계정으로 GHCR에 한 번 로그인한다(`read:packages` 권한 PAT): `docker login ghcr.io -u mike-urssu`
- Jenkins의 SSH 공개키를 배포 계정의 `~/.ssh/authorized_keys`에 넣는다.

### 3. Jenkins
- 필요한 플러그인: Pipeline, Git(GitHub), Credentials Binding, SSH Agent, JUnit
- 빌드 에이전트 요구사항: JDK 21, Docker(Testcontainers용, 이미지 빌드에는 필요 없음), `ssh`/`scp`, `curl`, 레지스트리와 Docker Hub(베이스 이미지)로 나가는 네트워크
- Credentials 등록 (ID는 `Jenkinsfile` 상단과 같아야 한다)

  | ID | 종류 | 내용 |
  |----|------|------|
  | `ghcr` | Username with password | GitHub 사용자명 + PAT(`write:packages`) |
  | `eol-deploy-ssh` | SSH Username with private key | 운영 서버 배포 계정의 개인키 |
- Pipeline job 생성: "Pipeline script from SCM" → `https://github.com/mike-urssu/oss-eol-poc-backend`, 브랜치 `main`, Script Path `Jenkinsfile`
- GitHub 저장소에 Jenkins webhook(`https://<jenkins>/github-webhook/`)을 등록하거나 job에서 Poll SCM을 설정한다.

## 로컬에서 이미지 확인
레지스트리 없이 로컬 Docker에 이미지를 만들어 볼 수 있다.
```bash
./gradlew jibDockerBuild -Djib.to.image=eol-poc-backend:local
```

## 배포
`main`에 push하면 테스트와 이미지 push까지 자동으로 진행되고, **Approve 단계에서 사람이 승인해야** 운영 서버에 배포된다. 이미지 태그는 커밋 해시 앞 8자리다.

## 롤백
운영 서버에서 이전 태그로 되돌린다. 태그는 Jenkins 빌드 기록이나 레지스트리에서 확인한다.
```bash
cd /opt/eol-poc
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<이전 태그>/' image.env
docker compose --env-file image.env up -d
```
DB 스키마는 되돌려지지 않는다. Flyway 마이그레이션을 추가한 배포를 롤백할 때는 이전 버전이 새 스키마에서도 동작하는지 먼저 확인한다.

## 운영 확인
```bash
cd /opt/eol-poc
docker compose --env-file image.env ps
docker compose --env-file image.env logs -f app
curl -s https://oss-poc.jjoon.cloud/api/opensources
```
