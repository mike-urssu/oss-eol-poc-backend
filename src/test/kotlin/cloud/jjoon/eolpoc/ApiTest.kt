package cloud.jjoon.eolpoc

import cloud.jjoon.eolpoc.extraction.ExtractedVersion
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Quoted
import cloud.jjoon.eolpoc.support.FixtureServer
import cloud.jjoon.eolpoc.support.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.Test
import kotlin.test.assertEquals

@AutoConfigureMockMvc
class ApiTest : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc

    private fun register(slug: String, name: String) {
        mvc.post("/api/opensources") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug": "$slug", "name": "$name", "homepageUrl": "https://example.org/$slug"}"""
        }.andExpect { status { isCreated() } }
        mvc.post("/api/opensources/$slug/sources") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url": "${FixtureServer.url("/$slug")}", "collectorType": "WEB", "trustLevel": "OFFICIAL"}"""
        }.andExpect { status { isCreated() } }
    }

    /** 원문 한 줄에 "버전 | 종료일"을 두고 그 줄을 근거로 쓰는 AI 결과를 만든다. */
    private fun collectWith(slug: String, vararg versionToEol: Pair<String, String?>) {
        FixtureServer.body = versionToEol.joinToString("") { (v, eol) -> "<p>$v | ${eol ?: "-"}</p>" }
        extractor.result = ExtractionResult(
            versionToEol.map { (v, eol) -> ExtractedVersion(v, null, null, eol?.let { Quoted(it, "$v | $it") }, null, null) },
        )
        mvc.post("/api/opensources/$slug/collections").andExpect { status { isAccepted() } }
    }

    @Test
    fun `헬스 체크는 DB 연결을 포함해 UP을 돌려준다`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `목록은 이름 검색과 EOL 상태 필터를 지원한다`() {
        register("postgresql", "PostgreSQL")
        register("openssl", "OpenSSL")
        collectWith("postgresql", "16" to "2028-11-09", "12" to "2024-11-21")
        collectWith("openssl", "3.0" to "2026-09-07")

        mvc.get("/api/opensources?q=ssl").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].slug") { value("openssl") }
        }
        mvc.get("/api/opensources?q=postgres").andExpect {
            jsonPath("$[0].slug") { value("postgresql") }
            jsonPath("$[0].versionCount") { value(2) }
            jsonPath("$[0].latestVersion") { value("16") }
            jsonPath("$[0].eolSummary.EOL") { value(1) }
            jsonPath("$[0].eolSummary.SUPPORTED") { value(1) }
            jsonPath("$[0].collectionStatus") { value("SUCCESS") }
        }
        mvc.get("/api/opensources?eolStatus=EOL").andExpect {
            jsonPath("$.length()") { value(2) }
        }
        mvc.get("/api/opensources?eolStatus=SUPPORTED").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].slug") { value("postgresql") }
        }
    }

    @Test
    fun `목록은 수집 상태로 필터한다`() {
        register("postgresql", "PostgreSQL")
        register("openssl", "OpenSSL")
        collectWith("postgresql", "16" to "2028-11-09")
        FixtureServer.status = 500
        mvc.post("/api/opensources/openssl/collections").andExpect { status { isAccepted() } }

        mvc.get("/api/opensources?collectionStatus=FAILED").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].slug") { value("openssl") }
            jsonPath("$[0].collectionError") { value(org.hamcrest.Matchers.containsString("HTTP 500")) }
        }
    }

    @Test
    fun `상세의 버전 목록은 버전 내림차순이고 날짜는 정밀도와 함께 내려간다`() {
        register("postgresql", "PostgreSQL")
        collectWith("postgresql", "9.6" to "2021-11-11", "10" to "2022-11", "9" to null)

        mvc.get("/api/opensources/postgresql").andExpect {
            status { isOk() }
            jsonPath("$.homepageUrl") { value("https://example.org/postgresql") }
            jsonPath("$.versions[0].version") { value("10") }
            jsonPath("$.versions[1].version") { value("9.6") }
            jsonPath("$.versions[2].version") { value("9") }
            jsonPath("$.versions[0].eolDate.date") { value("2022-11-30") }
            jsonPath("$.versions[0].eolDate.precision") { value("MONTH") }
            jsonPath("$.versions[0].sourceUrls[0]") { value(FixtureServer.url("/postgresql")) }
            jsonPath("$.versions[2].eolStatus") { value("UNKNOWN") }
            jsonPath("$.sources[0].lastCollection.status") { value("SUCCESS") }
        }
    }

    @Test
    fun `버전 상세는 판정 사유와 근거를 내려준다`() {
        register("openssl", "OpenSSL")
        collectWith("openssl", "3.0" to "2026-11-25")

        mvc.get("/api/opensources/openssl/versions/3.0").andExpect {
            status { isOk() }
            jsonPath("$.eolStatus.status") { value("EOL_SOON") }
            jsonPath("$.eolStatus.message") { value("종료일까지 45일 (기준 90일)") }
            jsonPath("$.evidence[0].quote") { value("3.0 | 2026-11-25") }
            jsonPath("$.validation.status") { value("APPROVED") }
        }
    }

    @Test
    fun `없는 Opensource는 404 문제 상세로 응답한다`() {
        mvc.get("/api/opensources/nope").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Opensource를 찾을 수 없습니다: nope") }
        }
    }

    @Test
    fun `수집 중에 다시 수집을 요청하면 409`() {
        register("postgresql", "PostgreSQL")
        FixtureServer.body = "<p>x</p>"
        var secondStatus = 0
        extractor.onExtract = {
            secondStatus = mvc.post("/api/opensources/postgresql/collections").andReturn().response.status
        }

        mvc.post("/api/opensources/postgresql/collections").andExpect {
            status { isAccepted() }
            jsonPath("$.collectionIds.length()") { value(1) }
        }

        assertEquals(409, secondStatus)
    }

    @Test
    fun `검토 대기 후보를 API로 조회하고 승인한다`() {
        register("postgresql", "PostgreSQL")
        collectWith("postgresql", "16" to "2028-11-09")
        collectWith("postgresql", "16" to "2028-11-16")

        mvc.get("/api/candidates").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].opensourceSlug") { value("postgresql") }
            jsonPath("$[0].changes[0].field") { value("EOL_DATE") }
            jsonPath("$[0].changes[0].current") { value("2028-11-09") }
            jsonPath("$[0].changes[0].proposed") { value("2028-11-16") }
        }
        val id = mvc.get("/api/candidates").andReturn().response.contentAsString.let { Regex("\"id\":(\\d+)").find(it)!!.groupValues[1] }

        mvc.post("/api/candidates/$id/approve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"comment": "공지 확인"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.validationStatus") { value("APPROVED") }
            jsonPath("$.reviewedBy") { value("admin") }
        }
        mvc.post("/api/candidates/$id/approve").andExpect { status { isConflict() } }
        mvc.get("/api/opensources/postgresql/versions/16").andExpect {
            jsonPath("$.eolDate.date") { value("2028-11-16") }
            jsonPath("$.history[0].changedBy") { value("admin") }
        }
    }

    @Test
    fun `등록 입력을 검증한다`() {
        mvc.post("/api/opensources") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug": "Bad Slug", "name": "x"}"""
        }.andExpect { status { isBadRequest() } }

        register("postgresql", "PostgreSQL")
        mvc.post("/api/opensources") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug": "postgresql", "name": "dup"}"""
        }.andExpect { status { isConflict() } }

        mvc.post("/api/opensources/postgresql/sources") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url": "file:///etc/passwd", "collectorType": "WEB", "trustLevel": "OFFICIAL"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `비활성화한 출처는 수집하지 않는다`() {
        register("postgresql", "PostgreSQL")
        mvc.put("/api/sources/1") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url": "${FixtureServer.url("/postgresql")}", "collectorType": "WEB", "trustLevel": "OFFICIAL", "active": false}"""
        }.andExpect { status { isOk() } }

        mvc.post("/api/opensources/postgresql/collections").andExpect {
            status { isAccepted() }
            jsonPath("$.collectionIds.length()") { value(0) }
        }
    }

    @Test
    fun `수집 원본을 근거 추적용으로 조회한다`() {
        register("postgresql", "PostgreSQL")
        collectWith("postgresql", "16" to "2028-11-09")

        mvc.get("/api/opensources/postgresql/collections").andExpect {
            jsonPath("$[0].status") { value("SUCCESS") }
            jsonPath("$[0].aiModel") { value("fake-model") }
        }
        mvc.get("/api/collections/1/content").andExpect {
            jsonPath("$.rawContent") { value("<p>16 | 2028-11-09</p>") }
            jsonPath("$.contentText") { value("16 | 2028-11-09") }
        }
    }
}
