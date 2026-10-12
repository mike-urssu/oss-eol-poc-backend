package cloud.jjoon.eolpoc.collection

import cloud.jjoon.eolpoc.candidate.ReviewService
import cloud.jjoon.eolpoc.collection.domain.CollectionStatus
import cloud.jjoon.eolpoc.collection.domain.Trigger
import cloud.jjoon.eolpoc.extraction.ExtractedVersion
import cloud.jjoon.eolpoc.extraction.ExtractionException
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Quoted
import cloud.jjoon.eolpoc.lifecycle.EolStatus
import cloud.jjoon.eolpoc.lifecycle.LifecycleQueryService
import cloud.jjoon.eolpoc.opensource.OpensourceService
import cloud.jjoon.eolpoc.opensource.api.SourceRequest
import cloud.jjoon.eolpoc.opensource.domain.CollectorType
import cloud.jjoon.eolpoc.opensource.domain.SourceConfig
import cloud.jjoon.eolpoc.opensource.domain.TrustLevel
import cloud.jjoon.eolpoc.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionPipelineTest : IntegrationTest() {
    @Autowired lateinit var opensources: OpensourceService
    @Autowired lateinit var collections: CollectionOrchestrator
    @Autowired lateinit var query: LifecycleQueryService
    @Autowired lateinit var reviews: ReviewService

    private fun collect() = collections.trigger("postgresql", null, Trigger.MANUAL)
    private fun lastRun() = query.collections("postgresql", 1).single()

    private val sourceUrl = "https://endoflife.date/postgresql"

    /** AI가 web fetch로 가져온 본문 */
    private val page = """
        Version | Current minor | Supported | First Release | Final Release
        17 | 17.6 | Yes | September 26, 2024 | November 8, 2029
        16 | 16.10 | Yes | September 14, 2023 | November 9, 2028
    """.trimIndent()

    private val row16 = "16 | 16.10 | Yes | September 14, 2023 | November 9, 2028"

    /** 모든 값의 근거를 같은 표 행(eolQuote)으로 인용한다. */
    private fun pg16(eol: String = "2028-11-09", eolQuote: String = row16) = ExtractedVersion(
        version = "16",
        latestPatchVersion = Quoted("16.10", eolQuote),
        releaseDate = Quoted("2023-09-14", eolQuote),
        eolDate = Quoted(eol, eolQuote),
        securityEolDate = null,
        support = null,
    )

    @BeforeEach
    fun registerPostgres() {
        opensources.create("postgresql", "PostgreSQL", "https://www.postgresql.org", null)
        opensources.addSource(
            "postgresql",
            SourceRequest(sourceUrl, CollectorType.WEB, TrustLevel.OFFICIAL, SourceConfig(versionPattern = """\d+""")),
        )
        extractor.page = page
    }

    @Test
    fun `처음 수집하면 승인된 값이 근거와 함께 조회된다`() {
        extractor.result = ExtractionResult(listOf(pg16()))

        collections.trigger("postgresql", null, Trigger.MANUAL)

        val detail = query.versionDetail("postgresql", "16")
        assertEquals("16.10", detail.latestPatchVersion)
        assertEquals("2028-11-09", detail.eolDate?.date.toString())
        assertEquals(EolStatus.SUPPORTED, detail.eolStatus.status)
        val eolEvidence = detail.evidence.single { it.field == "EOL_DATE" }
        assertEquals(sourceUrl, eolEvidence.sourceUrl)
        assertEquals(row16, eolEvidence.quote)
        assertEquals(
            listOf("EOL_DATE" to "2028-11-09"),
            detail.history.filter { it.field == "EOL_DATE" }.map { it.field to it.newValue },
        )
        // 출처 URL로 본문을 가져와, 그 본문 그대로 추출에 넘긴다
        assertEquals(listOf(sourceUrl), extractor.fetches)
        assertEquals(page, extractor.requests.single().contentText)
        assertEquals(150L to 15L, lastRun().aiInputTokens to lastRun().aiOutputTokens, "조회와 추출 사용량의 합")
    }

    @Test
    fun `같은 원본을 다시 수집하면 추출을 생략하고 이력이 늘지 않는다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()
        val historyBefore = query.versionDetail("postgresql", "16").history

        collect()

        assertTrue(lastRun().skippedUnchanged)
        assertEquals(2, extractor.fetches.size, "본문은 매번 가져온다")
        assertEquals(1, extractor.requests.size, "추출은 처음 한 번만")
        assertEquals(50L to 5L, lastRun().aiInputTokens to lastRun().aiOutputTokens, "본문 조회 호출의 사용량만 기록")
        assertEquals(historyBefore, query.versionDetail("postgresql", "16").history)
    }

    @Test
    fun `원본이 같아도 출처 설정이 바뀌면 다시 추출한다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()

        opensources.updateSource(
            lastRun().sourceId,
            SourceRequest(sourceUrl, CollectorType.WEB, TrustLevel.OFFICIAL, SourceConfig(versionPattern = """\d+(\.\d+)?""")),
        )
        collect()

        assertEquals(false, lastRun().skippedUnchanged)
        assertEquals(2, extractor.requests.size)
    }

    @Test
    fun `원본이 바뀌어도 값이 같으면 이력이 늘지 않는다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()
        val historyBefore = query.versionDetail("postgresql", "16").history

        extractor.page = page + "\nLast updated today"
        collect()

        assertEquals(false, lastRun().skippedUnchanged)
        assertEquals(historyBefore, query.versionDetail("postgresql", "16").history)
    }

    @Test
    fun `승인된 종료일이 바뀌면 검토 대상이 되고 승인해야 반영된다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()

        val changedRow = "16 | 16.10 | Yes | September 14, 2023 | November 16, 2028"
        extractor.page = page.replace("November 9, 2028", "November 16, 2028")
        extractor.result = ExtractionResult(listOf(pg16(eol = "2028-11-16", eolQuote = changedRow)))
        collect()

        val pending = query.versionDetail("postgresql", "16")
        assertEquals("2028-11-09", pending.eolDate?.date.toString(), "승인 전에는 현재 값이 유지된다")
        assertEquals(
            listOf(ChangeSummary("EOL_DATE", "2028-11-09", "2028-11-16")),
            pending.pendingReview!!.changes.filter { it.field == "EOL_DATE" }.map { ChangeSummary(it.field, it.current, it.proposed) },
        )
        assertEquals("REVIEW_REQUIRED", query.detail("postgresql").versions.single { it.version == "16" }.validationStatus?.name)

        reviews.approve(pending.pendingReview.candidateId, "kim", "공지 확인")

        val approved = query.versionDetail("postgresql", "16")
        assertEquals("2028-11-16", approved.eolDate?.date.toString())
        assertNull(approved.pendingReview)
        val change = approved.history.first { it.field == "EOL_DATE" }
        assertEquals(Triple("2028-11-09", "2028-11-16", "kim"), Triple(change.oldValue, change.newValue, change.changedBy))
        assertEquals(listOf(changedRow), approved.evidence.filter { it.field == "EOL_DATE" }.map { it.quote })
    }

    @Test
    fun `거부한 후보는 반영되지 않는다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()
        extractor.page = page.replace("November 9, 2028", "November 16, 2028")
        extractor.result = ExtractionResult(
            listOf(pg16(eol = "2028-11-16", eolQuote = "16 | 16.10 | Yes | September 14, 2023 | November 16, 2028")),
        )
        collect()
        val candidateId = query.versionDetail("postgresql", "16").pendingReview!!.candidateId

        reviews.reject(candidateId, null, "오기재")

        val detail = query.versionDetail("postgresql", "16")
        assertEquals("2028-11-09", detail.eolDate?.date.toString())
        assertNull(detail.pendingReview)
        assertFailsWith<ResponseStatusException> { reviews.approve(candidateId, null, null) }
    }

    @Test
    fun `출처 수집이 실패해도 기존 EOL 상태는 유지되고 목록에 실패가 보인다`() {
        extractor.result = ExtractionResult(listOf(pg16()))
        collect()

        extractor.fetchFailure = ExtractionException("FETCH_ERROR", "출처를 가져오지 못함: url_not_accessible ($sourceUrl)")
        collect()

        val run = lastRun()
        assertEquals(CollectionStatus.FAILED, run.status)
        assertEquals("FETCH_ERROR", run.errorCode)
        assertEquals(EolStatus.SUPPORTED, query.versionDetail("postgresql", "16").eolStatus.status)
        val summary = query.list(null, null, null).single()
        assertEquals(CollectionStatus.FAILED, summary.collectionStatus)
        assertEquals(mapOf(EolStatus.SUPPORTED to 1), summary.eolSummary.filterValues { it > 0 })
    }

    @Test
    fun `AI 호출이 실패하면 수집은 실패로 기록된다`() {
        extractor.failure = ExtractionException("AI_REFUSED", "거절됨")

        collect()

        assertEquals(CollectionStatus.FAILED to "AI_REFUSED", lastRun().status to lastRun().errorCode)
        assertEquals(emptyList(), query.detail("postgresql").versions)
    }

    @Test
    fun `근거 문구가 원문에 없는 AI 결과는 반영되지 않는다`() {
        extractor.result = ExtractionResult(listOf(pg16(eolQuote = "PostgreSQL 16 is supported until November 9, 2028")))

        collect()

        assertEquals(CollectionStatus.SUCCESS, lastRun().status)
        assertEquals(emptyList(), query.detail("postgresql").versions)
    }

    @Test
    fun `검토가 필요한 신규 버전도 미확인 상태로 목록에 보인다`() {
        // 2045년 종료일은 비정상 범위라 검토 대상
        extractor.page = "Version 30 is supported until 2045-01-01."
        extractor.result = ExtractionResult(
            listOf(ExtractedVersion("30", null, null, Quoted("2045-01-01", "supported until 2045-01-01"), null, null)),
        )

        collect()

        val row = query.detail("postgresql").versions.single()
        assertEquals("30", row.version)
        assertEquals(EolStatus.UNKNOWN, row.eolStatus)
        assertEquals("REVIEW_REQUIRED", row.validationStatus?.name)
    }

    @Test
    fun `같은 버전에 새 검토 후보가 생기면 이전 후보는 대체된다`() {
        extractor.page = "Version 30 is supported until 2045-01-01."
        extractor.result = ExtractionResult(
            listOf(ExtractedVersion("30", null, null, Quoted("2045-01-01", "supported until 2045-01-01"), null, null)),
        )
        collect()
        val first = query.pendingCandidates().single().id

        extractor.page = "Version 30 is supported until 2046-01-01."
        extractor.result = ExtractionResult(
            listOf(ExtractedVersion("30", null, null, Quoted("2046-01-01", "supported until 2046-01-01"), null, null)),
        )
        collect()

        val pending = query.pendingCandidates().single()
        assertTrue(pending.id != first)
        assertFailsWith<ResponseStatusException> { reviews.approve(first, null, null) }
    }

    @Test
    fun `날짜를 확인할 수 없는 버전은 값 없이 미확인이다`() {
        extractor.page = "Version 9 exists."
        extractor.result = ExtractionResult(listOf(ExtractedVersion("9", null, null, null, null, null)))

        collect()

        val detail = query.versionDetail("postgresql", "9")
        assertNull(detail.eolDate)
        assertEquals(EolStatus.UNKNOWN, detail.eolStatus.status)
    }

    private data class ChangeSummary(val field: String, val current: String?, val proposed: String?)
}
