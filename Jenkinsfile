// 운영 배포 파이프라인: 테스트 → Jib으로 이미지 빌드·push → SSH로 서버에 배포 → 기동 확인 → GHCR 오래된 이미지 정리
// 처음 설정 방법은 docs/deploy.md 참고.
// Jenkins 시스템 환경 변수 DEPLOY_HOST(운영 서버 user@host), DEPLOY_PORT(SSH 포트)를 사용한다.
pipeline {
    agent any

    environment {
        REGISTRY          = 'ghcr.io'                       // GitHub Container Registry
        IMAGE             = "${REGISTRY}/mike-urssu/oss-eol-poc-backend"
        REGISTRY_CRED     = 'ghcr-credentials'              // Username with password: GitHub 사용자명 + PAT(write:packages)
        DEPLOY_SSH_CRED   = 'deploy-ssh-key'                // SSH Username with private key: 운영 서버 배포 계정의 개인키
        DEPLOY_DIR        = '/opt/projects/oss-eol-poc/backend'
        APP_URL           = 'https://oss-eol-api.jjoon.cloud'
        GHCR_PACKAGE      = 'oss-eol-poc-backend'
        KEEP_IMAGES       = '10'                            // GHCR에 남길 최신 이미지 수
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 40, unit: 'MINUTES')
    }

    stages {
        stage('Test') {
            // Testcontainers가 PostgreSQL 컨테이너를 띄우므로 에이전트에서 Docker를 쓸 수 있어야 한다.
            steps {
                sh './gradlew --no-daemon clean test'
            }
            post {
                always {
                    junit 'build/test-results/test/*.xml'
                }
            }
        }

        stage('Build & push image') {
            // Jib이 Docker 데몬 없이 이미지를 만들어 레지스트리에 바로 push한다(build.gradle.kts의 jib 설정).
            steps {
                script {
                    env.IMAGE_TAG = env.GIT_COMMIT.take(8)
                }
                withCredentials([usernamePassword(credentialsId: env.REGISTRY_CRED, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                    sh './gradlew --no-daemon jib -Djib.to.image="$IMAGE:$IMAGE_TAG"'
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: env.DEPLOY_SSH_CRED, keyFileVariable: 'SSH_KEY')]) {
                    sh '''
                        SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=accept-new"
                        scp $SSH_OPTS -P "$DEPLOY_PORT" deploy/compose.yml "$DEPLOY_HOST:$DEPLOY_DIR/compose.yml"
                        ssh $SSH_OPTS -p "$DEPLOY_PORT" "$DEPLOY_HOST" "
                            set -e
                            cd '$DEPLOY_DIR'
                            printf 'IMAGE=%s\\nIMAGE_TAG=%s\\n' '$IMAGE' '$IMAGE_TAG' > image.env
                            docker compose --env-file image.env pull
                            docker compose --env-file image.env up -d
                            # 이 프로젝트 라벨이 붙은 이미지 중 컨테이너가 쓰지 않는 것만 지운다(다른 프로젝트 이미지는 건드리지 않음).
                            docker image prune -af --filter label=cloud.jjoon.project=oss-eol-poc
                        "
                    '''
                }
            }
        }

        stage('Verify') {
            steps {
                // Flyway 마이그레이션까지 끝나야 응답하므로 넉넉히 기다린다.
                sh 'curl -fsS --retry 20 --retry-delay 6 --retry-all-errors "$APP_URL/api/opensources" > /dev/null'
            }
        }

        stage('Clean up registry') {
            // GHCR에서 최신 KEEP_IMAGES개만 남긴다. 정리에 실패해도 배포는 성공으로 둔다.
            // 생성일 기준 정렬은 build.gradle.kts의 ghcrCleanup 태스크가 한다. ghcr-credentials의 PAT에 delete:packages 권한이 필요하다.
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    withCredentials([usernamePassword(credentialsId: env.REGISTRY_CRED, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                        sh './gradlew --no-daemon ghcrCleanup'
                    }
                }
            }
        }
    }
}
