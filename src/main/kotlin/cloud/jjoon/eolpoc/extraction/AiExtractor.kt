package cloud.jjoon.eolpoc.extraction

import cloud.jjoon.eolpoc.common.EolProperties
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Claude로 원본 텍스트에서 버전·EOL 정보를 추출한다(설계 4.2).
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
            // 안전 분류기가 거절하면 서버가 다른 모델로 다시 시도한다. 실제 응답 모델은 ai_model에 기록된다.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: Exception) {
            throw ExtractionException("AI_ERROR", "AI 호출 실패: ${e.message}", e)
        }
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

    private fun userMessage(r: ExtractionRequest) = buildString {
        appendLine("Opensource: ${r.opensourceName}")
        appendLine("출처 URL: ${r.sourceUrl}")
        r.versionHint?.let { appendLine("버전 단위: $it") }
        appendLine()
        appendLine("<document>")
        appendLine(r.contentText)
        append("</document>")
    }
}
