package cloud.jjoon.eolpoc.opensource.domain

/** source_registry.config (jsonb). 설계 4.1 */
data class SourceConfig(
    val selector: String? = null,
    val versionHint: String? = null,
    val versionPattern: String? = null,
)
