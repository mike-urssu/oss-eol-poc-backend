package cloud.jjoon.eolpoc.support

import cloud.jjoon.eolpoc.extraction.Extraction
import cloud.jjoon.eolpoc.extraction.ExtractionRequest
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Extractor
import cloud.jjoon.eolpoc.extraction.Fetched

/** AI 경계(Anthropic API)만 대체한다. 가져올 본문(page)과 추출 결과를 테스트에서 정하고, 받은 요청은 기록해 둔다. */
class FakeExtractor : Extractor {
    var page = ""
    var fetchFailure: RuntimeException? = null
    var result = ExtractionResult(emptyList())
    var failure: RuntimeException? = null
    /** 추출 도중(수집 진행 중) 실행할 동작. 동시 수집 테스트에 쓴다. */
    var onExtract: (() -> Unit)? = null
    val fetches = mutableListOf<String>()
    val requests = mutableListOf<ExtractionRequest>()

    override fun fetch(url: String): Fetched {
        fetches += url
        fetchFailure?.let { throw it }
        return Fetched(page, model = "fake-model", inputTokens = 50, outputTokens = 5)
    }

    override fun extract(request: ExtractionRequest): Extraction {
        requests += request
        onExtract?.invoke()
        failure?.let { throw it }
        return Extraction(result, model = "fake-model", inputTokens = 100, outputTokens = 10)
    }

    fun reset() {
        page = ""
        fetchFailure = null
        result = ExtractionResult(emptyList())
        failure = null
        onExtract = null
        fetches.clear()
        requests.clear()
    }
}
