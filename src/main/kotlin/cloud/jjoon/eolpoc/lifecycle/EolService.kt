package cloud.jjoon.eolpoc.lifecycle

import cloud.jjoon.eolpoc.candidate.Candidate
import cloud.jjoon.eolpoc.candidate.CandidateRepository
import cloud.jjoon.eolpoc.opensource.OpensourceRepository
import cloud.jjoon.eolpoc.opensource.SourceRepository
import cloud.jjoon.eolpoc.validation.Reason
import cloud.jjoon.eolpoc.validation.ValidationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 현재 데이터(oss_version, oss_lifecycle)를 바꾸는 유일한 경로 (설계 4.5). 호출자의 트랜잭션 안에서 실행된다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class EolService(
    private val versions: VersionRepository,
    private val evidence: EvidenceRepository,
    private val history: ChangeHistoryRepository,
    private val opensources: OpensourceRepository,
    private val sources: SourceRepository,
    private val candidates: CandidateRepository,
) {
    /**
     * 수집에서 나온 후보를 검증 결과에 따라 처리한다.
     * 승인은 바로 반영하고, 검토 필요는 같은 출처·버전의 이전 대기 후보를 대체(D9)하며 버전만 등록한다(D10). 거부는 기록만 남는다.
     */
    fun accept(candidate: Candidate, now: Instant) {
        when (candidate.validationStatus) {
            ValidationStatus.APPROVED -> apply(candidate, SYSTEM, now)
            ValidationStatus.REVIEW_REQUIRED -> {
                supersedeOlder(candidate, now)
                ensureVersion(candidate.opensourceId, candidate.version)
            }
            ValidationStatus.REJECTED -> Unit
        }
    }

    /** 후보 값을 현재 데이터에 반영한다. 바뀐 필드만 이력을 남기고, 근거는 확인 시각을 갱신한다(STO-03, 04, 05). */
    fun apply(candidate: Candidate, changedBy: String, now: Instant): Version {
        val version = ensureVersion(candidate.opensourceId, candidate.version)
        val old = version.values()
        val new = candidate.values()
        for (field in old.changedFields(new)) {
            history.save(ChangeHistory(version.id!!, field, old.display(field), new.display(field), candidate.id!!, changedBy, now))
        }
        version.assign(new, now)
        version.lastVerifiedAt = now

        val source = sources.getReferenceById(candidate.sourceId)
        for (e in candidate.evidence) {
            evidence.upsert(
                version.id!!, e.field.name, e.value, candidate.sourceId, source.url, e.quote,
                candidate.extractionMethod.name, candidate.collectionId, now,
            )
        }
        return version
    }

    /** 원본이 바뀌지 않아 추출을 생략한 수집: 확인 시각만 갱신한다. */
    fun markVerified(sourceId: Long, now: Instant) {
        evidence.touchVerifiedBySource(sourceId, now)
        versions.touchVerifiedBySource(sourceId, now)
    }

    /** STO-08: 처음 발견된 버전을 관리 대상으로 등록한다. */
    private fun ensureVersion(opensourceId: Long, version: String): Version =
        versions.findByOpensourceIdAndVersion(opensourceId, version)
            ?: versions.save(Version(opensources.getReferenceById(opensourceId), version))

    private fun supersedeOlder(newer: Candidate, now: Instant) {
        candidates.findAllByOpensourceIdAndSourceIdAndVersionAndValidationStatusAndReviewedAtIsNull(
            newer.opensourceId, newer.sourceId, newer.version, ValidationStatus.REVIEW_REQUIRED,
        ).filter { it.id != newer.id }.forEach {
            it.validationReasons += Reason("SUPERSEDED", null, "후보 ${newer.id}(으)로 대체됨", ValidationStatus.REJECTED)
            it.close(ValidationStatus.REJECTED, SYSTEM, null, now)
        }
    }

    companion object {
        const val SYSTEM = "SYSTEM"
    }
}
