# 운영 배포 (Jenkins)

`main` 브랜치를 Jenkins가 빌드해 운영 서버 1대에 Docker로 배포하고, Traefik을 통해 `https://oss-eol-api.jjoon.cloud`로 서비스한다. 비밀값(DB 비밀번호, Anthropic API 키)은 Jenkins를 거치지 않고 운영 서버의 `app.env`에만 둔다.

```
GitHub ──webhook──▶ Jenkins: Test → Build & push image(Jib) → Deploy(SSH) → Verify → Clean up registry
                                         │                              │
                                  ghcr.io ◀──────────────────── pull ── 운영 서버 (docker compose)

 운영 서버:  Traefik ──(proxy 네트워크)──▶ app ──(postgresql 네트워크)──▶ PostgreSQL 컨테이너
             oss-eol-api.jjoon.cloud
```

| 파일 | 역할 |
|------|------|
| `Jenkinsfile` | 파이프라인. 등록된 credentials와 Jenkins 시스템 환경 변수(`DEPLOY_HOST`, `DEPLOY_PORT`)를 사용한다. |
| `build.gradle.kts`의 `jib` | `eclipse-temurin:21-jre` 기반 이미지를 Docker 데몬 없이 만들어 push한다. `nobody`(65534) 사용자로 실행한다. 대상 플랫폼은 운영 서버에 맞춘 linux/arm64. |
| `deploy/compose.yml` | 운영 서버에서 실행할 서비스 정의. 배포 때마다 Jenkins가 복사한다. |
| `deploy/app.env.example` | 운영 서버 `app.env`의 예시. |

## 처음 한 번 설정

### 1. 운영 DB (postgresql 네트워크의 기존 PostgreSQL)
- 데이터베이스와 계정을 만든다. 계정에 스키마 생성 권한이 있어야 한다(기동 시 Flyway가 테이블과 시드를 만든다).
  ```sql
  -- 이름에 '-'가 있어 큰따옴표로 감싼다. 비밀번호는 운영 서버에서 정하고 app.env의 DB_PASSWORD에 넣는다.
  CREATE USER "oss-eol" WITH PASSWORD '...';
  CREATE DATABASE "oss-eol" OWNER "oss-eol";
  ```
- `app.env`의 `DB_URL` 호스트는 `postgresql` 네트워크 안의 PostgreSQL 컨테이너 이름(`postgresql`)이다.

### 2. 운영 서버
- Docker Engine과 Docker Compose 플러그인, 그리고 외부 네트워크 `proxy`(Traefik)와 `postgresql`이 이미 있어야 한다.
- Traefik은 entrypoint `websecure`, 인증서 resolver `letsencrypt`를 쓴다(`deploy/compose.yml` 라벨).
- DNS에서 `oss-eol-api.jjoon.cloud`가 운영 서버를 가리키게 한다.
- 배포 계정(예: `deploy`)을 만들고 `docker` 그룹에 넣는다.
- 배포 디렉터리를 만들고 `app.env`를 작성한다.
  ```bash
  sudo mkdir -p /opt/projects/oss-eol-poc/backend && sudo chown deploy: /opt/projects/oss-eol-poc/backend
  cp app.env.example /opt/projects/oss-eol-poc/backend/app.env   # 값 채우기
  chmod 600 /opt/projects/oss-eol-poc/backend/app.env
  ```
- 배포 계정으로 GHCR에 한 번 로그인한다(`read:packages` 권한 PAT): `docker login ghcr.io -u mike-urssu`
- Jenkins의 SSH 공개키를 배포 계정의 `~/.ssh/authorized_keys`에 넣는다.

### 3. Jenkins
- 필요한 플러그인: Pipeline, Git(GitHub), Credentials Binding, JUnit
- 빌드 에이전트 요구사항: JDK 21, Docker(Testcontainers용, 이미지 빌드에는 필요 없음), `ssh`/`scp`, `curl`, 레지스트리와 Docker Hub(베이스 이미지)로 나가는 네트워크
- Credentials (등록되어 있음, ID는 `Jenkinsfile` 상단과 같아야 한다)

  | ID | 종류 | 내용 |
  |----|------|------|
  | `ghcr-credentials` | Username with password | GitHub 사용자명 + classic PAT(`write:packages`, `delete:packages`) |
  | `deploy-ssh-key` | SSH Username with private key | 운영 서버 배포 계정의 개인키 |
- 시스템 환경 변수 (Manage Jenkins → System → Global properties, 등록되어 있음)

  | 이름 | 내용 |
  |------|------|
  | `DEPLOY_HOST` | 운영 서버 `user@host` |
  | `DEPLOY_PORT` | 운영 서버 SSH 포트 |
- Multibranch Pipeline job 생성: Branch Source GitHub → `https://github.com/mike-urssu/oss-eol-poc-backend`, Script Path `Jenkinsfile`. 모든 브랜치가 테스트되고, 이미지 빌드·배포는 `Jenkinsfile`의 `when { branch 'main' }` 조건으로 `main`에서만 실행된다.
- GitHub 저장소에 Jenkins webhook(`https://<jenkins>/github-webhook/`)을 등록하거나 job에서 Poll SCM을 설정한다.

## 로컬에서 이미지 확인
레지스트리 없이 로컬 Docker에 이미지를 만들어 볼 수 있다.
```bash
./gradlew jibDockerBuild -Djib.to.image=eol-poc-backend:local
```

## 배포
`main`에 push하면 테스트를 통과한 경우 **승인 없이 운영 서버까지 자동 배포**된다. 이미지 태그는 커밋 해시 앞 8자리다.

## 이미지 정리
- **GHCR**: 배포가 끝나면 `Clean up registry` 단계가 Gradle `ghcrCleanup` 태스크로 이미지 버전을 생성일 기준으로 정렬해 최신 10개(`KEEP_IMAGES`)만 남기고 지운다. 정리에 실패해도 빌드는 성공으로 남고 해당 단계만 실패로 표시된다.
  삭제 대상만 미리 보려면: `GHCR_PACKAGE=oss-eol-poc-backend REG_PASS=<PAT> ./gradlew ghcrCleanup -PdryRun=true`
- **운영 서버**: 배포할 때 `docker image prune -af --filter label=cloud.jjoon.project=oss-eol-poc`로 이 프로젝트 이미지 중 컨테이너가 쓰지 않는 것만 지운다. 라벨을 붙이기 전에 받은 이미지는 대상이 아니므로 한 번 직접 지운다.

## 롤백
운영 서버에서 이전 태그로 되돌린다. 태그는 Jenkins 빌드 기록이나 레지스트리에서 확인한다. GHCR에는 최신 10개만 남으므로 그보다 오래된 버전으로는 롤백할 수 없다.
```bash
cd /opt/projects/oss-eol-poc/backend
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<이전 태그>/' image.env
docker compose --env-file image.env up -d
```
DB 스키마는 되돌려지지 않는다. Flyway 마이그레이션을 추가한 배포를 롤백할 때는 이전 버전이 새 스키마에서도 동작하는지 먼저 확인한다.

## 운영 확인
```bash
cd /opt/projects/oss-eol-poc/backend
docker compose --env-file image.env ps
docker compose --env-file image.env logs -f app
curl -s https://oss-eol-api.jjoon.cloud/actuator/health   # {"status":"UP"}이면 DB 연결까지 정상
```
