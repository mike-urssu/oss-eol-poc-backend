package cloud.jjoon.eolpoc.extraction

import cloud.jjoon.eolpoc.common.EolProperties
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebFetchTool20250910
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Claude로 출처 본문을 가져오고(web fetch), 그 본문에서 버전·EOL 정보를 추출한다(설계 4.1, 4.2).
 * 구조화 출력으로 스키마를 강제하고, 결과는 후보일 뿐 DB를 직접 바꾸지 않는다.
 */
@Component
class AiExtractor(private val props: EolProperties, private val json: JsonMapper) : Extractor {
    // ANTHROPIC_API_KEY 등 SDK 기본 자격 증명을 쓴다. 키가 없는 환경(테스트)에서도 기동되도록 처음 호출할 때 만든다.
    private val client: AnthropicClient by lazy { AnthropicOkHttpClient.fromEnv() }
    private val systemPrompt: String by lazy {
        ClassPathResource("prompts/${props.ai.promptVersion}.md").getContentAsString(Charsets.UTF_8)
    }

    /**
     * 출력 스키마는 직접 정의한다. Kotlin 클래스에서 자동 생성하면 nullable 필드도 null을 허용하지 않는 필수 값이 되어,
     * 문서에 없는 값을 AI가 지어내게 된다(STO-06 위반).
     */
    private val schema: JsonOutputFormat.Schema by lazy {
        val text = ClassPathResource("prompts/${props.ai.promptVersion}.schema.json").getContentAsString(Charsets.UTF_8)
        val map = json.readValue(text, Map::class.java)
        JsonOutputFormat.Schema.builder()
            .additionalProperties(map.entries.associate { (k, v) -> k as String to JsonValue.from(v) })
            .build()
    }

    /**
     * web fetch 도구로 출처 본문만 가져온다. 추출은 하지 않으므로 출력 토큰이 거의 들지 않는다.
     * 가져온 본문은 응답의 web_fetch_tool_result에 그대로 담겨 오므로, 근거 문구 검증(VAL-08)에 그대로 쓴다.
     * 도구는 출처 도메인 한 곳, 한 번만 허용해 본문에 섞인 지시로 다른 주소를 가져오지 못하게 한다.
     */
    override fun fetch(url: String): Fetched {
        val params = MessageCreateParams.builder()
            .model(props.ai.model)
            .maxTokens(FETCH_MAX_TOKENS)
            .system(FETCH_PROMPT)
            .addUserMessage(url)
            .addTool(
                WebFetchTool20250910.builder()
                    .maxUses(1L)
                    .allowedDomains(listOf(URI(url).host))
                    .maxContentTokens(FETCH_MAX_CONTENT_TOKENS)
                    .build(),
            )
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .withFallback()
            .build()

        val message = call(params)
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw ExtractionException("AI_REFUSED", "AI가 출처 조회를 거절함: ${message.stopDetails()}")
        }
        val result = message.content().firstNotNullOfOrNull { it.webFetchToolResult().orElse(null) }?.content()
            ?: throw ExtractionException("FETCH_ERROR", "AI가 출처를 가져오지 않음 ($url)")
        result.webFetchToolResultErrorBlock().ifPresent {
            throw ExtractionException("FETCH_ERROR", "출처를 가져오지 못함: ${it.errorCode()} ($url)")
        }
        val text = result.webFetchBlock().get().content().source().text().orElse(null)?.data()
            ?: throw ExtractionException("FETCH_ERROR", "텍스트가 아닌 출처(PDF 등)는 지원하지 않음 ($url)")
        if (text.isBlank()) throw ExtractionException("EMPTY_CONTENT", "가져온 본문이 비어 있음 ($url)")
        return Fetched(text, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens())
    }

    override fun extract(request: ExtractionRequest): Extraction {
        val params = MessageCreateParams.builder()
            .model(props.ai.model)
            .maxTokens(props.ai.maxTokens)
            .system(systemPrompt)
            .addUserMessage(userMessage(request))
            .outputConfig(
                OutputConfig.builder()
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .effort(OutputConfig.Effort.of(props.ai.effort))
                    .build(),
            )
            .withFallback()
            .build()

        val message = call(params)
        when (message.stopReason().orElse(null)) {
            StopReason.MAX_TOKENS -> throw ExtractionException("AI_OUTPUT_TRUNCATED", "AI 출력이 max_tokens(${props.ai.maxTokens})에서 잘림")
            StopReason.REFUSAL -> throw ExtractionException("AI_REFUSED", "AI가 요청을 거절함: ${message.stopDetails()}")
            else -> Unit
        }
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        val result = try {
            json.readValue(text, ExtractionResult::class.java)
        } catch (e: Exception) {
            throw ExtractionException("AI_ERROR", "AI 응답이 스키마와 맞지 않음: ${e.message}", e)
        }
        return Extraction(result, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens())
    }

    private fun call(params: MessageCreateParams): Message = try {
        client.messages().create(params)
    } catch (e: Exception) {
        throw ExtractionException("AI_ERROR", "AI 호출 실패: ${e.message}", e)
    }

    // 안전 분류기가 거절하면 서버가 다른 모델로 다시 시도한다. 실제 응답 모델은 ai_model에 기록된다.
    private fun MessageCreateParams.Builder.withFallback() = this
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

    private fun userMessage(r: ExtractionRequest) = buildString {
        appendLine("Opensource: ${r.opensourceName}")
        appendLine("출처 URL: ${r.sourceUrl}")
        r.versionHint?.let { appendLine("버전 단위: $it") }
        appendLine()
        appendLine("<document>")
        appendLine(r.contentText)
        append("</document>")
    }

    companion object {
        private const val FETCH_MAX_TOKENS = 2048L

        // ponytail: 출처 한 페이지의 본문 상한. 넘으면 뒷부분이 잘려 그 안의 버전이 빠지므로, 더 긴 출처가 생기면 늘린다.
        private const val FETCH_MAX_CONTENT_TOKENS = 50_000L

        private const val FETCH_PROMPT =
            "사용자가 준 URL을 web_fetch 도구로 한 번 가져온다. 가져온 뒤에는 '완료'라고만 답한다. 내용을 요약하거나 해석하지 않는다."
    }
}
