package cloud.jjoon.eolpoc.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor

@TestConfiguration(proxyBeanMethods = false)
class TestBeans {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:17-alpine")

    @Bean
    @Primary
    fun fakeExtractor() = FakeExtractor()

    /** 기준일 2026-10-11 (Asia/Seoul) */
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneId.of("Asia/Seoul"))

    /** 수동 수집을 동기로 실행해 테스트에서 결과를 바로 확인한다. */
    @Bean
    @Primary
    fun syncCollectionExecutor() = Executor { it.run() }
}
