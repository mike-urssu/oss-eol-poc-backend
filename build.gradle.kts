import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.4.5"
}

group = "cloud.jjoon"
version = "0.0.1-SNAPSHOT"
description = "eol-poc-backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.anthropic:anthropic-java:2.34.0")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 이미지 이름·태그와 레지스트리 계정은 Jenkins가 넘긴다(Jenkinsfile 참고).
jib {
    from {
        image = "eclipse-temurin:21-jre"
        // 운영 서버가 ARM64다. Jib 기본값(amd64)이면 서버에서 에뮬레이션으로 느리게 돈다.
        platforms {
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = "eol-poc-backend"
        auth {
            username = System.getenv("REG_USER")
            password = System.getenv("REG_PASS")
        }
    }
    container {
        user = "65534:65534" // nobody
        // 운영 서버에서 이 프로젝트 이미지만 골라 정리(docker image prune --filter label=...)하기 위한 라벨
        labels = mapOf("cloud.jjoon.project" to "oss-eol-poc")
        ports = listOf("8080")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// GHCR 이미지 버전을 생성일 기준으로 정렬해 최신 KEEP_IMAGES개만 남기고 지운다(Jenkins 'Clean up registry' 단계).
// 필요한 환경 변수: GHCR_PACKAGE, REG_PASS(delete:packages 권한 PAT), KEEP_IMAGES(기본 10)
// 지우지 않고 대상만 보려면: ./gradlew ghcrCleanup -PdryRun=true
tasks.register("ghcrCleanup") {
    group = "deployment"
    val pkg = providers.environmentVariable("GHCR_PACKAGE")
    val token = providers.environmentVariable("REG_PASS")
    val keep = providers.environmentVariable("KEEP_IMAGES").map(String::toInt).orElse(10)
    val dryRun = providers.gradleProperty("dryRun").map(String::toBoolean).orElse(false)
    doLast {
        val http = HttpClient.newHttpClient()
        val api = "https://api.github.com/user/packages/container/${pkg.get()}/versions"
        fun request(uri: String) = HttpRequest.newBuilder(URI(uri))
            .header("Authorization", "Bearer ${token.get()}")
            .header("Accept", "application/vnd.github+json")

        val list = http.send(request("$api?per_page=100").GET().build(), HttpResponse.BodyHandlers.ofString())
        check(list.statusCode() == 200) { "버전 목록 조회 실패: HTTP ${list.statusCode()} ${list.body()}" }
        @Suppress("UNCHECKED_CAST")
        val versions = JsonSlurper().parseText(list.body()) as List<Map<String, Any?>>

        val expired = versions.sortedByDescending { Instant.parse(it["created_at"] as String) }.drop(keep.get())
        logger.lifecycle("버전 ${versions.size}개 중 ${expired.size}개 삭제 (최신 ${keep.get()}개 유지)")
        for (v in expired) {
            @Suppress("UNCHECKED_CAST")
            val tags = ((v["metadata"] as Map<String, Any?>?)?.get("container") as Map<String, Any?>?)?.get("tags")
            logger.lifecycle("${if (dryRun.get()) "[dry-run] " else ""}삭제: ${v["id"]} ${v["created_at"]} $tags")
            if (dryRun.get()) continue
            val deleted = http.send(request("$api/${v["id"]}").DELETE().build(), HttpResponse.BodyHandlers.ofString())
            check(deleted.statusCode() == 204) { "버전 ${v["id"]} 삭제 실패: HTTP ${deleted.statusCode()} ${deleted.body()}" }
        }
    }
}
