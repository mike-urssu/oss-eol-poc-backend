package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.opensource.TrustLevel

data class SourceRules(val trustLevel: TrustLevel, val versionPattern: Regex?)
