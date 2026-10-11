package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.Field

data class Reason(val code: String, val field: Field?, val message: String, val status: ValidationStatus)
