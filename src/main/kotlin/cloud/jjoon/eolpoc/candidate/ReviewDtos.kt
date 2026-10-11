package cloud.jjoon.eolpoc.candidate

import cloud.jjoon.eolpoc.validation.Reason
import cloud.jjoon.eolpoc.validation.ValidationStatus
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
