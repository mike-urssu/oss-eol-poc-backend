package cloud.jjoon.eolpoc.validation.domain

import cloud.jjoon.eolpoc.lifecycle.domain.Field

data class Reason(val code: String, val field: Field?, val message: String, val status: ValidationStatus)
