package cloud.jjoon.eolpoc.support

import cloud.jjoon.eolpoc.extraction.Extraction
import cloud.jjoon.eolpoc.extraction.ExtractionRequest
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Extractor

/** AI 경계(Azure OpenAI API)만 대체한다. 받은 요청은 기록해 둔다. */
class FakeExtractor : Extractor {
    var result = ExtractionResult(emptyList())
    var failure: RuntimeException? = null
    /** 추출 도중(수집 진행 중) 실행할 동작. 동시 수집 테스트에 쓴다. */
    var onExtract: (() -> Unit)? = null
    val requests = mutableListOf<ExtractionRequest>()

    override fun extract(request: ExtractionRequest): Extraction {
        requests += request
        onExtract?.invoke()
        failure?.let { throw it }
        return Extraction(result, model = "fake-model", inputTokens = 100, outputTokens = 10)
    }

    fun reset() {
        result = ExtractionResult(emptyList())
        failure = null
        onExtract = null
        requests.clear()
    }
}
