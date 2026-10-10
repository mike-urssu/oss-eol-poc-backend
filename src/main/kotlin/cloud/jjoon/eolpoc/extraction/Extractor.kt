package cloud.jjoon.eolpoc.extraction

enum class ExtractionMethod { AI, RULE }

data class ExtractionRequest(
    val opensourceName: String,
    val sourceUrl: String,
    val versionHint: String?,
    val contentText: String,
)

data class Extraction(
    val result: ExtractionResult,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/** 원본 텍스트에서 버전·EOL 정보를 뽑는다. 기본은 AI, 예외 출처만 전용 Parser(설계 4.2). */
interface Extractor {
    fun extract(request: ExtractionRequest): Extraction
}

class ExtractionException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
