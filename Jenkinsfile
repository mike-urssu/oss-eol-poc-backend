// 운영 배포 파이프라인: 테스트 → Jib으로 이미지 빌드·push → (승인) → SSH로 서버에 배포 → 기동 확인
// 처음 설정 방법은 docs/deploy.md 참고. TODO 표시된 값을 환경에 맞게 바꾼다.
pipeline {
    agent any

    environment {
        REGISTRY          = 'ghcr.io'                       // GitHub Container Registry
        IMAGE             = "${REGISTRY}/mike-urssu/oss-eol-poc-backend"
        REGISTRY_CRED     = 'ghcr'                          // Jenkins credentials ID: GitHub 사용자명 + PAT(write:packages)
        DEPLOY_SSH_CRED   = 'eol-deploy-ssh'                // TODO: Jenkins credentials ID (SSH private key)
        DEPLOY_HOST       = 'deploy@eol-server.internal'    // TODO: 운영 서버 계정@호스트
        DEPLOY_DIR        = '/opt/eol-poc'
        APP_URL           = 'https://oss-poc.jjoon.cloud'
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

        stage('Approve') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: "운영 서버에 ${env.IMAGE_TAG}을(를) 배포할까요?", ok: '배포'
                }
            }
        }

        stage('Deploy') {
            steps {
                sshagent(credentials: [env.DEPLOY_SSH_CRED]) {
                    sh '''
                        scp -o StrictHostKeyChecking=accept-new deploy/docker-compose.yml "$DEPLOY_HOST:$DEPLOY_DIR/docker-compose.yml"
                        ssh -o StrictHostKeyChecking=accept-new "$DEPLOY_HOST" "
                            set -e
                            cd '$DEPLOY_DIR'
                            printf 'IMAGE=%s\\nIMAGE_TAG=%s\\n' '$IMAGE' '$IMAGE_TAG' > image.env
                            docker compose --env-file image.env pull
                            docker compose --env-file image.env up -d
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
    }
}
