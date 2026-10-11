package cloud.jjoon.eolpoc.candidate

import cloud.jjoon.eolpoc.extraction.ExtractionMethod
import cloud.jjoon.eolpoc.lifecycle.DatePrecision
import cloud.jjoon.eolpoc.lifecycle.PreciseDate
import cloud.jjoon.eolpoc.lifecycle.SupportDeclared
import cloud.jjoon.eolpoc.lifecycle.VersionValues
import cloud.jjoon.eolpoc.validation.Evidence
import cloud.jjoon.eolpoc.validation.Reason
import cloud.jjoon.eolpoc.validation.ValidatedCandidate
import cloud.jjoon.eolpoc.validation.ValidationStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

/** 정규화·검증을 거친 후보(eol_candidate). 현재 데이터에는 APPROVED일 때만 반영된다. */
@Entity
@Table(name = "eol_candidate")
class Candidate(
    val collectionId: Long,
    val opensourceId: Long,
    val sourceId: Long,
    val version: String,
    val latestPatchVersion: String?,
    val releaseDate: LocalDate?,
    @Enumerated(EnumType.STRING) val releaseDatePrecision: DatePrecision?,
    val eolDate: LocalDate?,
    @Enumerated(EnumType.STRING) val eolDatePrecision: DatePrecision?,
    val securityEolDate: LocalDate?,
    @Enumerated(EnumType.STRING) val securityEolDatePrecision: DatePrecision?,
    @Enumerated(EnumType.STRING) val supportDeclared: SupportDeclared?,
    @JdbcTypeCode(SqlTypes.JSON) val evidence: List<Evidence>,
    @Enumerated(EnumType.STRING) val extractionMethod: ExtractionMethod,
    @Enumerated(EnumType.STRING) var validationStatus: ValidationStatus,
    @JdbcTypeCode(SqlTypes.JSON) var validationReasons: List<Reason>,
    val createdAt: Instant,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var reviewedBy: String? = null
    var reviewComment: String? = null
    var reviewedAt: Instant? = null

    val pendingReview get() = validationStatus == ValidationStatus.REVIEW_REQUIRED && reviewedAt == null

    fun values() = VersionValues(
        latestPatchVersion = latestPatchVersion,
        releaseDate = releaseDate?.let { PreciseDate(it, releaseDatePrecision!!) },
        eolDate = eolDate?.let { PreciseDate(it, eolDatePrecision!!) },
        securityEolDate = securityEolDate?.let { PreciseDate(it, securityEolDatePrecision!!) },
        support = supportDeclared,
    )

    fun close(status: ValidationStatus, reviewer: String, comment: String?, now: Instant) {
        validationStatus = status
        reviewedBy = reviewer
        reviewComment = comment
        reviewedAt = now
    }

    companion object {
        fun of(
            validated: ValidatedCandidate, collectionId: Long, opensourceId: Long, sourceId: Long,
            method: ExtractionMethod, now: Instant,
        ): Candidate {
            val v = validated.values
            return Candidate(
                collectionId, opensourceId, sourceId, validated.version,
                v.latestPatchVersion,
                v.releaseDate?.date, v.releaseDate?.precision,
                v.eolDate?.date, v.eolDate?.precision,
                v.securityEolDate?.date, v.securityEolDate?.precision,
                v.support, validated.evidence, method, validated.status, validated.reasons, now,
            )
        }
    }
}

interface CandidateRepository : JpaRepository<Candidate, Long> {
    fun findAllByOpensourceIdAndVersionOrderByIdDesc(opensourceId: Long, version: String): List<Candidate>
    fun findAllByOpensourceIdOrderByIdDesc(opensourceId: Long): List<Candidate>
    fun findAllByOpensourceIdAndSourceIdAndVersionAndValidationStatusAndReviewedAtIsNull(
        opensourceId: Long, sourceId: Long, version: String, status: ValidationStatus,
    ): List<Candidate>
    fun findAllByValidationStatusAndReviewedAtIsNullOrderById(status: ValidationStatus): List<Candidate>
}
