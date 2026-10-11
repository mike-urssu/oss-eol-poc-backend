package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.domain.VersionValues
import cloud.jjoon.eolpoc.validation.domain.Evidence
import cloud.jjoon.eolpoc.validation.domain.Reason
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus

data class ValidatedCandidate(
    val version: String,
    val values: VersionValues,
    val evidence: List<Evidence>,
    val status: ValidationStatus,
    val reasons: List<Reason>,
)
