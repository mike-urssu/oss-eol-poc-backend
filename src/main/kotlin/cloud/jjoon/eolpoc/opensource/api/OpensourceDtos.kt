package cloud.jjoon.eolpoc.opensource.api

import cloud.jjoon.eolpoc.opensource.domain.CollectorType
import cloud.jjoon.eolpoc.opensource.domain.SourceConfig
import cloud.jjoon.eolpoc.opensource.domain.TrustLevel

data class OpensourceRequest(
    val slug: String = "",
    val name: String,
    val homepageUrl: String? = null,
    val description: String? = null,
    val active: Boolean = true,
)

data class OpensourceResponse(val slug: String, val name: String, val homepageUrl: String?, val description: String?, val active: Boolean)

data class SourceResponse(
    val id: Long,
    val url: String,
    val collectorType: CollectorType,
    val trustLevel: TrustLevel,
    val config: SourceConfig,
    val parserKey: String?,
    val active: Boolean,
)

data class SourceRequest(
    val url: String,
    val collectorType: CollectorType,
    val trustLevel: TrustLevel,
    val config: SourceConfig = SourceConfig(),
    val parserKey: String? = null,
    val active: Boolean = true,
)
