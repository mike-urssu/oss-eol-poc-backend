package cloud.jjoon.eolpoc.extraction

data class ExtractionRequest(
    val opensourceName: String,
    val sourceUrl: String,
    val versionHint: String?,
    val contentText: String,
)
