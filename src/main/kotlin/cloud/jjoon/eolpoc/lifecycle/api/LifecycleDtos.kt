package cloud.jjoon.eolpoc.lifecycle.api

import cloud.jjoon.eolpoc.collection.domain.CollectionStatus
import cloud.jjoon.eolpoc.extraction.domain.ExtractionMethod
import cloud.jjoon.eolpoc.lifecycle.EolJudgement
import cloud.jjoon.eolpoc.lifecycle.EolStatus
import cloud.jjoon.eolpoc.lifecycle.domain.DatePrecision
import cloud.jjoon.eolpoc.lifecycle.domain.SupportDeclared
import cloud.jjoon.eolpoc.opensource.domain.CollectorType
import cloud.jjoon.eolpoc.opensource.domain.TrustLevel
import cloud.jjoon.eolpoc.validation.domain.Reason
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus
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
