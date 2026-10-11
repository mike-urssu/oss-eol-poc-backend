package cloud.jjoon.eolpoc.lifecycle

import cloud.jjoon.eolpoc.candidate.Candidate
import cloud.jjoon.eolpoc.candidate.CandidateRepository
import cloud.jjoon.eolpoc.collection.CollectionRun
import cloud.jjoon.eolpoc.collection.CollectionRunRepository
import cloud.jjoon.eolpoc.collection.CollectionStatus
import cloud.jjoon.eolpoc.common.EolProperties
import cloud.jjoon.eolpoc.common.notFound
import cloud.jjoon.eolpoc.extraction.ExtractionMethod
import cloud.jjoon.eolpoc.opensource.CollectorType
import cloud.jjoon.eolpoc.opensource.Opensource
import cloud.jjoon.eolpoc.opensource.OpensourceRepository
import cloud.jjoon.eolpoc.opensource.SourceRepository
import cloud.jjoon.eolpoc.opensource.TrustLevel
import cloud.jjoon.eolpoc.validation.DatePrecision
import cloud.jjoon.eolpoc.validation.PreciseDate
import cloud.jjoon.eolpoc.validation.Reason
import cloud.jjoon.eolpoc.validation.ValidationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class DateDto(val date: LocalDate, val precision: DatePrecision)

data class OpensourceSummary(
    val slug: String,
    val name: String,
    val latestVersion: String?,
    val versionCount: Int,
    val eolSummary: Map<EolStatus, Int>,
    val lastCollectedAt: Instant?,
    val collectionStatus: CollectionStatus?,
    val collectionError: String?,
)

data class CollectionSummary(
    val id: Long,
    val sourceId: Long,
    val trigger: String,
    val status: CollectionStatus?,
    val skippedUnchanged: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val candidateCount: Int?,
    val aiModel: String?,
    val aiInputTokens: Long?,
    val aiOutputTokens: Long?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

data class SourceDto(
    val id: Long,
    val url: String,
    val collectorType: CollectorType,
    val trustLevel: TrustLevel,
    val active: Boolean,
    val lastCollection: CollectionSummary?,
)

data class VersionRow(
    val version: String,
    val latestPatchVersion: String?,
    val releaseDate: DateDto?,
    val eolDate: DateDto?,
    val securityEolDate: DateDto?,
    val eolStatus: EolStatus,
    val validationStatus: ValidationStatus?,
    val sourceUrls: List<String>,
)

data class OpensourceDetail(
    val slug: String,
    val name: String,
    val homepageUrl: String?,
    val latestVersion: String?,
    val sources: List<SourceDto>,
    val versions: List<VersionRow>,
)

data class EvidenceDto(
    val field: String,
    val value: String,
    val sourceUrl: String,
    val quote: String,
    val extractionMethod: ExtractionMethod,
    val verifiedAt: Instant,
    val collectionId: Long,
)

data class ValidationDto(val candidateId: Long, val status: ValidationStatus, val reasons: List<Reason>)

data class ChangeDto(val field: String, val current: String?, val proposed: String?, val quote: String?)

data class PendingReviewDto(val candidateId: Long, val reasons: List<Reason>, val changes: List<ChangeDto>)

data class HistoryDto(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val changedBy: String,
    val changedAt: Instant,
    val candidateId: Long,
)

data class PendingCandidateDto(
    val id: Long,
    val opensourceSlug: String,
    val version: String,
    val reasons: List<Reason>,
    val changes: List<ChangeDto>,
    val createdAt: Instant,
)

data class VersionDetail(
    val version: String,
    val latestPatchVersion: String?,
    val releaseDate: DateDto?,
    val eolDate: DateDto?,
    val securityEolDate: DateDto?,
    val supportDeclared: SupportDeclared?,
    val eolStatus: EolJudgement,
    val lastVerifiedAt: Instant?,
    val evidence: List<EvidenceDto>,
    val validation: ValidationDto?,
    val pendingReview: PendingReviewDto?,
    val history: List<HistoryDto>,
)

/** 화면 1~3 조회 (설계 6.1~6.3). EOL 상태는 조회 시점에 계산한다. */
@Service
@Transactional(readOnly = true)
class LifecycleQueryService(
    private val opensources: OpensourceRepository,
    private val sources: SourceRepository,
    private val versions: VersionRepository,
    private val evidence: EvidenceRepository,
    private val history: ChangeHistoryRepository,
    private val candidates: CandidateRepository,
    private val runs: CollectionRunRepository,
    private val props: EolProperties,
    private val clock: Clock,
) {
    // ponytail: 전체를 읽어 메모리에서 집계·필터한다. Opensource 수백 개 이상이면 SQL 집계로 바꾼다(설계 10장).
    fun list(q: String?, eolStatus: EolStatus?, collectionStatus: CollectionStatus?): List<OpensourceSummary> =
        opensources.findAll().sortedBy { it.name.lowercase() }
            .filter { q.isNullOrBlank() || it.name.contains(q, ignoreCase = true) || it.slug.contains(q, ignoreCase = true) }
            .map { summarize(it) }
            .filter { eolStatus == null || (it.eolSummary[eolStatus] ?: 0) > 0 }
            .filter { collectionStatus == null || it.collectionStatus == collectionStatus }

    fun detail(slug: String): OpensourceDetail {
        val opensource = find(slug)
        val all = versions.findAllByOpensourceId(opensource.id!!)
        val evidenceByVersion = evidence.findAllByVersionIdIn(all.map { it.id!! }).groupBy { it.versionId }
        val candidatesByVersion = candidates.findAllByOpensourceIdOrderByIdDesc(opensource.id!!).groupBy { it.version }
        val rows = all.sortedWith(compareByDescending(VersionComparator) { it.version }).map { v ->
            VersionRow(
                version = v.version,
                latestPatchVersion = v.latestPatchVersion,
                releaseDate = v.values().releaseDate?.toDto(),
                eolDate = v.values().eolDate?.toDto(),
                securityEolDate = v.values().securityEolDate?.toDto(),
                eolStatus = judge(v).status,
                validationStatus = validationStatus(candidatesByVersion[v.version].orEmpty()),
                sourceUrls = currentEvidence(v, evidenceByVersion[v.id].orEmpty()).map { it.sourceUrl }.distinct(),
            )
        }
        val sourceDtos = sources.findAllByOpensourceIdOrderById(opensource.id!!).map {
            SourceDto(it.id!!, it.url, it.collectorType, it.trustLevel, it.active,
                runs.findFirstBySourceIdAndFinishedAtIsNotNullOrderByIdDesc(it.id!!)?.toSummary())
        }
        return OpensourceDetail(opensource.slug, opensource.name, opensource.homepageUrl, latestVersion(all), sourceDtos, rows)
    }

    fun versionDetail(slug: String, version: String): VersionDetail {
        val opensource = find(slug)
        val v = versions.findByOpensourceIdAndVersion(opensource.id!!, version)
            ?: throw notFound("버전을 찾을 수 없습니다: $slug $version")
        val values = v.values()
        val versionCandidates = candidates.findAllByOpensourceIdAndVersionOrderByIdDesc(opensource.id!!, version)
        val latest = versionCandidates.firstOrNull { !it.pendingReview } ?: versionCandidates.firstOrNull()
        val pending = versionCandidates.firstOrNull { it.pendingReview }
        return VersionDetail(
            version = v.version,
            latestPatchVersion = values.latestPatchVersion,
            releaseDate = values.releaseDate?.toDto(),
            eolDate = values.eolDate?.toDto(),
            securityEolDate = values.securityEolDate?.toDto(),
            supportDeclared = values.support,
            eolStatus = judge(v),
            lastVerifiedAt = v.lastVerifiedAt,
            evidence = currentEvidence(v, evidence.findAllByVersionId(v.id!!)).map {
                EvidenceDto(it.field.name, it.value, it.sourceUrl, it.quote, it.extractionMethod, it.verifiedAt, it.collectionId)
            },
            validation = latest?.let { ValidationDto(it.id!!, it.validationStatus, it.validationReasons) },
            pendingReview = pending?.let { PendingReviewDto(it.id!!, it.validationReasons, changes(v, it)) },
            history = history.findAllByVersionIdOrderByIdDesc(v.id!!).map {
                HistoryDto(it.field.name, it.oldValue, it.newValue, it.changedBy, it.changedAt, it.candidateId)
            },
        )
    }

    fun collections(slug: String, limit: Int): List<CollectionSummary> =
        runs.findAllByOpensourceIdOrderByIdDesc(find(slug).id!!, org.springframework.data.domain.Limit.of(limit)).map { it.toSummary() }

    /** 검토 대기 후보 목록 (REV-01). */
    fun pendingCandidates(): List<PendingCandidateDto> {
        val slugs = opensources.findAll().associate { it.id!! to it.slug }
        return candidates.findAllByValidationStatusAndReviewedAtIsNullOrderById(ValidationStatus.REVIEW_REQUIRED).map { c ->
            val current = versions.findByOpensourceIdAndVersion(c.opensourceId, c.version)
            PendingCandidateDto(c.id!!, slugs.getValue(c.opensourceId), c.version, c.validationReasons, changes(current, c), c.createdAt)
        }
    }

    private fun summarize(opensource: Opensource): OpensourceSummary {
        val all = versions.findAllByOpensourceId(opensource.id!!)
        val lastRuns = sources.findAllByOpensourceIdOrderById(opensource.id!!).filter { it.active }
            .mapNotNull { runs.findFirstBySourceIdAndFinishedAtIsNotNullOrderByIdDesc(it.id!!) }
        val failed = lastRuns.firstOrNull { it.status == CollectionStatus.FAILED }
        return OpensourceSummary(
            slug = opensource.slug,
            name = opensource.name,
            latestVersion = latestVersion(all),
            versionCount = all.size,
            eolSummary = EolStatus.entries.associateWith { s -> all.count { judge(it).status == s } },
            lastCollectedAt = lastRuns.mapNotNull { it.finishedAt }.maxOrNull(),
            collectionStatus = when {
                lastRuns.isEmpty() -> null
                failed != null -> CollectionStatus.FAILED
                else -> CollectionStatus.SUCCESS
            },
            collectionError = failed?.errorMessage,
        )
    }

    private fun find(slug: String) = opensources.findBySlug(slug) ?: throw notFound("Opensource를 찾을 수 없습니다: $slug")

    private fun judge(v: Version) =
        EolStatusCalculator.judge(v.eolDate, v.supportDeclared, LocalDate.now(clock), props.soonThresholdDays)

    private fun latestVersion(all: List<Version>) = all.map { it.latestPatchVersion ?: it.version }.maxWithOrNull(VersionComparator)

    /** D1: 미처리 검토 후보가 있으면 검토 필요, 없으면 가장 최근 후보의 상태. */
    private fun validationStatus(versionCandidates: List<Candidate>): ValidationStatus? =
        if (versionCandidates.any { it.pendingReview }) ValidationStatus.REVIEW_REQUIRED
        else versionCandidates.firstOrNull()?.validationStatus

    /** 현재 값을 뒷받침하는 근거만 고른다(D8). */
    private fun currentEvidence(v: Version, records: List<EvidenceRecord>): List<EvidenceRecord> {
        val values = v.values()
        return records.filter { it.value == values.display(it.field) }
    }

    private fun changes(v: Version?, candidate: Candidate): List<ChangeDto> {
        val current = v?.values() ?: cloud.jjoon.eolpoc.validation.VersionValues()
        val proposed = candidate.values()
        return current.changedFields(proposed).map { field ->
            ChangeDto(field.name, current.display(field), proposed.display(field), candidate.evidence.firstOrNull { it.field == field }?.quote)
        }
    }

    private fun PreciseDate.toDto() = DateDto(date, precision)

    private fun CollectionRun.toSummary() = CollectionSummary(
        id!!, sourceId, trigger.name, status, skippedUnchanged, errorCode, errorMessage, candidateCount,
        aiModel, aiInputTokens, aiOutputTokens, startedAt, finishedAt,
    )
}
