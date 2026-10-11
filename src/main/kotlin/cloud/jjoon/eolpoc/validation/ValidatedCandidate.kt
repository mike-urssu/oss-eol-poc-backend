package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.VersionValues

data class ValidatedCandidate(
    val version: String,
    val values: VersionValues,
    val evidence: List<Evidence>,
    val status: ValidationStatus,
    val reasons: List<Reason>,
)
