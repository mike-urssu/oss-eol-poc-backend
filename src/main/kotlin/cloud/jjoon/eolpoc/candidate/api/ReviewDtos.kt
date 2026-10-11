package cloud.jjoon.eolpoc.candidate.api

import cloud.jjoon.eolpoc.validation.domain.Reason
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus
import java.time.Instant

data class ReviewRequest(val reviewer: String? = null, val comment: String? = null)

data class ReviewResponse(
    val id: Long,
    val version: String,
    val validationStatus: ValidationStatus,
    val reasons: List<Reason>,
    val reviewedBy: String?,
    val reviewComment: String?,
    val reviewedAt: Instant?,
)
