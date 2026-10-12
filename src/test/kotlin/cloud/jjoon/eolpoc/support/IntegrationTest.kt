package cloud.jjoon.eolpoc.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    properties = [
        "spring.flyway.locations=classpath:db/migration", // 시드 제외
    ],
)
@Import(TestBeans::class)
abstract class IntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var extractor: FakeExtractor

    @BeforeEach
    fun resetState() {
        jdbc.execute(
            "TRUNCATE opensource, source_registry, oss_version, oss_lifecycle, eol_collection, " +
                "eol_candidate, eol_evidence, eol_change_history RESTART IDENTITY CASCADE",
        )
        extractor.reset()
    }
}
