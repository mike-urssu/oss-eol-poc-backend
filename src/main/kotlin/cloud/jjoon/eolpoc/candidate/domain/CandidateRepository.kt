package cloud.jjoon.eolpoc.candidate.domain

import cloud.jjoon.eolpoc.validation.domain.ValidationStatus
import org.springframework.data.jpa.repository.JpaRepository

interface CandidateRepository : JpaRepository<Candidate, Long> {
    fun findAllByOpensourceIdAndVersionOrderByIdDesc(opensourceId: Long, version: String): List<Candidate>
    fun findAllByOpensourceIdOrderByIdDesc(opensourceId: Long): List<Candidate>
    fun findAllByOpensourceIdAndSourceIdAndVersionAndValidationStatusAndReviewedAtIsNull(
        opensourceId: Long, sourceId: Long, version: String, status: ValidationStatus,
    ): List<Candidate>
    fun findAllByValidationStatusAndReviewedAtIsNullOrderById(status: ValidationStatus): List<Candidate>
}
