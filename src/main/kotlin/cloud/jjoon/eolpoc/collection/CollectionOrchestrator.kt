package cloud.jjoon.eolpoc.collection

import cloud.jjoon.eolpoc.candidate.domain.Candidate
import cloud.jjoon.eolpoc.candidate.domain.CandidateRepository
import cloud.jjoon.eolpoc.collection.domain.CollectionRun
import cloud.jjoon.eolpoc.collection.domain.CollectionRunRepository
import cloud.jjoon.eolpoc.collection.domain.CollectionStatus
import cloud.jjoon.eolpoc.collection.domain.Trigger
import cloud.jjoon.eolpoc.common.EolProperties
import cloud.jjoon.eolpoc.common.conflict
import cloud.jjoon.eolpoc.common.notFound
import cloud.jjoon.eolpoc.extraction.Extraction
import cloud.jjoon.eolpoc.extraction.ExtractionException
import cloud.jjoon.eolpoc.extraction.ExtractionRequest
import cloud.jjoon.eolpoc.extraction.Extractor
import cloud.jjoon.eolpoc.extraction.Fetched
import cloud.jjoon.eolpoc.extraction.domain.ExtractionMethod
import cloud.jjoon.eolpoc.lifecycle.EolService
import cloud.jjoon.eolpoc.lifecycle.domain.VersionRepository
import cloud.jjoon.eolpoc.opensource.domain.OpensourceRepository
import cloud.jjoon.eolpoc.opensource.domain.SourceConfig
import cloud.jjoon.eolpoc.opensource.domain.SourceRepository
import cloud.jjoon.eolpoc.opensource.domain.TrustLevel
import cloud.jjoon.eolpoc.validation.CandidateValidator
import cloud.jjoon.eolpoc.validation.SourceRules
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
    private val extractor: Extractor,
    private val props: EolProperties,
    private val clock: Clock,
    private val tx: TransactionTemplate,
    private val collectionExecutor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ponytail: JVM 메모리 잠금이라 인스턴스 1대에서만 유효(설계 D11). 여러 대로 늘리면 PostgreSQL advisory lock으로 바꾼다.
    private val running = ConcurrentHashMap.newKeySet<Long>()

    /** 수동 재수집(SCH-02): 수집 행을 먼저 만들어 ID를 돌려주고 처리는 비동기로 한다. 이미 수집 중이면 409. */
    fun trigger(slug: String, sourceId: Long?, trigger: Trigger): List<Long> {
        val opensourceId = (opensources.findBySlug(slug) ?: throw notFound("Opensource를 찾을 수 없습니다: $slug")).id!!
        val targets = targetSourceIds(opensourceId, sourceId)
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
                createRuns(opensourceId, targetSourceIds(opensourceId, null), Trigger.SCHEDULED).forEach(::process)
            } finally {
                running.remove(opensourceId)
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun closeInterruptedRuns() {
        val closed = tx.execute { runs.closeInterrupted(Instant.now(clock)) }
        if (closed > 0) log.warn("끝나지 못한 수집 {}건을 INTERRUPTED로 닫았습니다", closed)
    }

    private fun targetSourceIds(opensourceId: Long, sourceId: Long?): List<Long> {
        val all = sources.findAllByOpensourceIdOrderById(opensourceId)
        val selected = if (sourceId == null) all.filter { it.active }
        else all.filter { it.id == sourceId }.ifEmpty { throw notFound("출처를 찾을 수 없습니다: $sourceId") }
        return selected.map { it.id!! }
    }

    private fun createRuns(opensourceId: Long, sourceIds: List<Long>, trigger: Trigger): List<Long> = tx.execute {
        sourceIds.map { runs.save(CollectionRun(opensourceId, it, trigger, Instant.now(clock))).id!! }
    }

    private fun process(runId: Long) {
        val (opensourceId, source) = tx.execute {
            val run = runs.findById(runId).orElseThrow()
            val s = sources.findById(run.sourceId).orElseThrow()
            run.opensourceId to SourceSnapshot(s.id!!, s.url, s.trustLevel, s.config, s.opensource.name)
        }
        MDC.putCloseable("collectionId", runId.toString()).use {
            var fetched: Fetched? = null
            try {
                // 본문만 가져오는 호출은 출력 토큰이 거의 없다. 본문이 그대로면 비싼 추출 호출을 건너뛴다(설계 D5).
                fetched = extractor.fetch(source.url)
                // 추출·검증에 쓰이는 출처 설정이 바뀌면 원본이 같아도 다시 처리한다
                val hash = sha256(listOf(props.ai.promptVersion, source.config, source.trustLevel, fetched.text).joinToString("\n"))

                val previous = runs.findFirstBySourceIdAndStatusAndIdLessThanOrderByIdDesc(source.id, CollectionStatus.SUCCESS, runId)
                if (previous?.contentHash == hash) {
                    finishSkipped(runId, source.id, fetched, hash)
                    return
                }
                val extraction = extractor.extract(
                    ExtractionRequest(source.opensourceName, source.url, source.config.versionHint, fetched.text),
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
                fetched.text,
                SourceRules(source.trustLevel, source.config.versionPattern?.let(::Regex)),
                { version -> versions.findByOpensourceIdAndVersion(opensourceId, version)?.values() },
                LocalDate.now(clock),
            )
            for (v in validated) {
                eolService.accept(candidates.save(Candidate.of(v, runId, opensourceId, source.id, ExtractionMethod.AI, now)), now)
            }
            runs.findById(runId).orElseThrow().apply {
                fill(fetched, hash)
                aiModel = extraction.model
                aiInputTokens = fetched.inputTokens + extraction.inputTokens
                aiOutputTokens = fetched.outputTokens + extraction.outputTokens
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
            errorCode = (e as? ExtractionException)?.code ?: "UNEXPECTED"
            errorMessage = e.message
            status = CollectionStatus.FAILED
            finishedAt = Instant.now(clock)
        }
    }

    /** 가져온 본문과 그 호출의 사용량을 기록한다. 추출까지 했으면 persist가 사용량을 합계로 덮어쓴다. */
    private fun CollectionRun.fill(fetched: Fetched, hash: String?) {
        contentText = fetched.text
        contentHash = hash
        promptVersion = props.ai.promptVersion
        aiModel = fetched.model
        aiInputTokens = fetched.inputTokens
        aiOutputTokens = fetched.outputTokens
    }

    private fun sha256(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private data class SourceSnapshot(
        val id: Long,
        val url: String,
        val trustLevel: TrustLevel,
        val config: SourceConfig,
        val opensourceName: String,
    )
}
