package cloud.jjoon.eolpoc.validation.domain

import cloud.jjoon.eolpoc.lifecycle.domain.Field

data class Evidence(val field: Field, val value: String, val quote: String)
