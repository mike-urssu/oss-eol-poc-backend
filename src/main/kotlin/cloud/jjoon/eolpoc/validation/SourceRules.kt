package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.opensource.domain.TrustLevel

data class SourceRules(val trustLevel: TrustLevel, val versionPattern: Regex?)
