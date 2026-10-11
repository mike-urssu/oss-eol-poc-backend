package cloud.jjoon.eolpoc.candidate

import cloud.jjoon.eolpoc.common.conflict
import cloud.jjoon.eolpoc.common.notFound
import cloud.jjoon.eolpoc.lifecycle.EolService
import cloud.jjoon.eolpoc.validation.ValidationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 검토 필요 후보의 승인·거부 (설계 4.8). */
@Service
@Transactional
class ReviewService(
    private val candidates: CandidateRepository,
    private val eolService: EolService,
    private val clock: Clock,
) {
    fun approve(id: Long, reviewer: String?, comment: String?): Candidate {
        val candidate = pending(id)
        val now = Instant.now(clock)
        eolService.apply(candidate, reviewer ?: DEFAULT_REVIEWER, now)
        candidate.close(ValidationStatus.APPROVED, reviewer ?: DEFAULT_REVIEWER, comment, now)
        return candidate
    }

    fun reject(id: Long, reviewer: String?, comment: String?): Candidate =
        pending(id).apply { close(ValidationStatus.REJECTED, reviewer ?: DEFAULT_REVIEWER, comment, Instant.now(clock)) }

    private fun pending(id: Long): Candidate {
        val candidate = candidates.findById(id).orElse(null) ?: throw notFound("후보를 찾을 수 없습니다: $id")
        if (!candidate.pendingReview) throw conflict("검토 대기 중인 후보가 아닙니다: $id (${candidate.validationStatus})")
        return candidate
    }

    companion object {
        const val DEFAULT_REVIEWER = "admin"
    }
}
