package cloud.jjoon.eolpoc.collection

import cloud.jjoon.eolpoc.candidate.Candidate
import cloud.jjoon.eolpoc.candidate.CandidateRepository
import cloud.jjoon.eolpoc.candidate.ReviewService
import cloud.jjoon.eolpoc.common.EolProperties
import cloud.jjoon.eolpoc.common.conflict
import cloud.jjoon.eolpoc.common.notFound
import cloud.jjoon.eolpoc.extraction.Extraction
import cloud.jjoon.eolpoc.extraction.ExtractionException
import cloud.jjoon.eolpoc.extraction.ExtractionMethod
import cloud.jjoon.eolpoc.extraction.ExtractionRequest
import cloud.jjoon.eolpoc.extraction.Extractor
import cloud.jjoon.eolpoc.lifecycle.EolService
import cloud.jjoon.eolpoc.lifecycle.VersionRepository
import cloud.jjoon.eolpoc.opensource.CollectorType
import cloud.jjoon.eolpoc.opensource.Opensource
import cloud.jjoon.eolpoc.opensource.OpensourceRepository
import cloud.jjoon.eolpoc.opensource.SourceConfig
import cloud.jjoon.eolpoc.opensource.SourceRepository
import cloud.jjoon.eolpoc.opensource.TrustLevel
import cloud.jjoon.eolpoc.validation.CandidateValidator
import cloud.jjoon.eolpoc.validation.SourceRules
import cloud.jjoon.eolpoc.validation.ValidationStatus
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** 수집 → 추출 → 검증 → 반영 순서를 관리한다(설계 3장). */
@Service
class CollectionOrchestrator(
    private val opensources: OpensourceRepository,
    private val sources: SourceRepository,
    private val runs: CollectionRunRepository,
    private val candidates: CandidateRepository,
    private val versions: VersionRepository,
    private val eolService: EolService,
    private val reviewService: ReviewService,
    private val extractor: Extractor,
    collectors: List<Collector>,
    private val props: EolProperties,
    private val clock: Clock,
    private val tx: TransactionTemplate,
    private val collectionExecutor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val collectors = collectors.associateBy { it.type }

    // ponytail: JVM 메모리 잠금이라 인스턴스 1대에서만 유효(설계 D11). 여러 대로 늘리면 PostgreSQL advisory lock으로 바꾼다.
    private val running = ConcurrentHashMap.newKeySet<Long>()

    /** 수동 재수집(SCH-02): 수집 행을 먼저 만들어 ID를 돌려주고 처리는 비동기로 한다. 이미 수집 중이면 409. */
    fun trigger(slug: String, sourceId: Long?, trigger: Trigger): List<Long> {
        val opensource = opensources.findBySlug(slug) ?: throw notFound("Opensource를 찾을 수 없습니다: $slug")
        val targets = targetSources(opensource, sourceId)
        val opensourceId = opensource.id!!
        if (!running.add(opensourceId)) throw conflict("이미 수집 중입니다: $slug")
        try {
            val ids = createRuns(opensourceId, targets, trigger)
            collectionExecutor.execute {
                try {
                    ids.forEach(::process)
                } finally {
                    running.remove(opensourceId)
                }
            }
            return ids
        } catch (e: Exception) {
            running.remove(opensourceId)
            throw e
        }
    }

    /** 정기 수집(SCH-01): 활성 Opensource를 순서대로 수집한다. 수집 중인 것은 건너뛴다. */
    fun collectAllActive() {
        for (opensource in opensources.findAllByActiveTrueOrderById()) {
            val opensourceId = opensource.id!!
            if (!running.add(opensourceId)) continue
            try {
                createRuns(opensourceId, targetSources(opensource, null), Trigger.SCHEDULED).forEach(::process)
            } finally {
                running.remove(opensourceId)
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun closeInterruptedRuns() {
        val closed = tx.execute { runs.closeInterrupted(Instant.now(clock)) } ?: 0
        if (closed > 0) log.warn("끝나지 못한 수집 {}건을 INTERRUPTED로 닫았습니다", closed)
    }

    private fun targetSources(opensource: Opensource, sourceId: Long?): List<SourceSnapshot> {
        val all = sources.findAllByOpensourceIdOrderById(opensource.id!!)
        val selected = if (sourceId == null) all.filter { it.active }
        else all.filter { it.id == sourceId }.ifEmpty { throw notFound("출처를 찾을 수 없습니다: $sourceId") }
        return selected.map { SourceSnapshot(it.id!!, it.url, it.collectorType, it.trustLevel, it.config, opensource.name) }
    }

    private fun createRuns(opensourceId: Long, targets: List<SourceSnapshot>, trigger: Trigger): List<Long> = tx.execute {
        targets.map { source ->
            val run = runs.save(CollectionRun(opensourceId, source.id, trigger, Instant.now(clock)))
            run.id!!
        }
    }!!

    private fun process(runId: Long) {
        val (opensourceId, source) = tx.execute {
            val run = runs.findById(runId).orElseThrow()
            val s = sources.findById(run.sourceId).orElseThrow()
            run.opensourceId to SourceSnapshot(s.id!!, s.url, s.collectorType, s.trustLevel, s.config, s.opensource.name)
        }!!
        MDC.putCloseable("collectionId", runId.toString()).use {
            var fetched: Fetched? = null
            try {
                val collector = collectors[source.collectorType]
                    ?: throw CollectionException("UNSUPPORTED_COLLECTOR", "지원하지 않는 수집 방식: ${source.collectorType}")
                fetched = collector.fetch(source.url, source.config)
                // 추출·검증에 쓰이는 출처 설정이 바뀌면 원본이 같아도 다시 처리한다
                val hash = sha256(listOf(props.ai.promptVersion, source.config, source.trustLevel, fetched.contentText).joinToString("\n"))

                val previous = runs.findFirstBySourceIdAndStatusAndIdLessThanOrderByIdDesc(source.id, CollectionStatus.SUCCESS, runId)
                if (previous?.contentHash == hash) {
                    finishSkipped(runId, source.id, fetched, hash)
                    return
                }
                val extraction = extractor.extract(
                    ExtractionRequest(source.opensourceName, source.url, source.config.versionHint, fetched.contentText),
                )
                persist(runId, opensourceId, source, fetched, hash, extraction)
            } catch (e: Exception) {
                log.warn("수집 실패: {}", e.message, e)
                finishFailed(runId, fetched, e)
            }
        }
    }

    private fun persist(runId: Long, opensourceId: Long, source: SourceSnapshot, fetched: Fetched, hash: String, extraction: Extraction) {
        tx.executeWithoutResult {
            val now = Instant.now(clock)
            val validated = CandidateValidator.validate(
                extraction.result,
                fetched.contentText,
                SourceRules(source.trustLevel, source.config.versionPattern?.let(::Regex)),
                { version -> versions.findByOpensourceIdAndVersion(opensourceId, version)?.values() },
                LocalDate.now(clock),
            )
            for (v in validated) {
                val candidate = candidates.save(Candidate.of(v, runId, opensourceId, source.id, ExtractionMethod.AI, now))
                when (candidate.validationStatus) {
                    ValidationStatus.APPROVED -> eolService.apply(candidate, SYSTEM, now)
                    ValidationStatus.REVIEW_REQUIRED -> {
                        reviewService.supersedeOlder(candidate, now)
                        eolService.ensureVersion(opensourceId, candidate.version) // D10: 검토 대기 신규 버전도 목록에 보인다
                    }
                    ValidationStatus.REJECTED -> Unit
                }
            }
            runs.findById(runId).orElseThrow().apply {
                fill(fetched, hash)
                aiModel = extraction.model
                aiInputTokens = extraction.inputTokens
                aiOutputTokens = extraction.outputTokens
                candidateCount = validated.size
                status = CollectionStatus.SUCCESS
                finishedAt = now
            }
        }
    }

    private fun finishSkipped(runId: Long, sourceId: Long, fetched: Fetched, hash: String) = tx.executeWithoutResult {
        val now = Instant.now(clock)
        eolService.markVerified(sourceId, now)
        runs.findById(runId).orElseThrow().apply {
            fill(fetched, hash)
            skippedUnchanged = true
            candidateCount = 0
            status = CollectionStatus.SUCCESS
            finishedAt = now
        }
    }

    private fun finishFailed(runId: Long, fetched: Fetched?, e: Exception) = tx.executeWithoutResult {
        runs.findById(runId).orElseThrow().apply {
            fetched?.let { fill(it, null) }
            errorCode = when (e) {
                is CollectionException -> e.code
                is ExtractionException -> e.code
                else -> "UNEXPECTED"
            }
            if (e is CollectionException) httpStatus = e.httpStatus ?: httpStatus
            errorMessage = e.message
            status = CollectionStatus.FAILED
            finishedAt = Instant.now(clock)
        }
    }

    private fun CollectionRun.fill(fetched: Fetched, hash: String?) {
        httpStatus = fetched.httpStatus
        rawContent = fetched.rawContent
        contentText = fetched.contentText
        contentHash = hash
        promptVersion = props.ai.promptVersion
    }

    private fun sha256(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private data class SourceSnapshot(
        val id: Long,
        val url: String,
        val collectorType: CollectorType,
        val trustLevel: TrustLevel,
        val config: SourceConfig,
        val opensourceName: String,
    )

    companion object {
        const val SYSTEM = "SYSTEM"
    }
}
