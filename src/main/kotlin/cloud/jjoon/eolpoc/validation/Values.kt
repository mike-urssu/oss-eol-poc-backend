package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.Field
import cloud.jjoon.eolpoc.lifecycle.VersionValues
import cloud.jjoon.eolpoc.opensource.TrustLevel

data class Evidence(val field: Field, val value: String, val quote: String)

enum class ValidationStatus { APPROVED, REVIEW_REQUIRED, REJECTED }

data class Reason(val code: String, val field: Field?, val message: String, val status: ValidationStatus)

data class SourceRules(val trustLevel: TrustLevel, val versionPattern: Regex?)

data class ValidatedCandidate(
    val version: String,
    val values: VersionValues,
    val evidence: List<Evidence>,
    val status: ValidationStatus,
    val reasons: List<Reason>,
)
