package cloud.jjoon.eolpoc.extraction

data class Extraction(
    val result: ExtractionResult,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
)
