package cloud.jjoon.eolpoc.extraction

import cloud.jjoon.eolpoc.common.EolProperties
import com.openai.azure.AzureUrlPathMode
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Azure OpenAI로 원본 텍스트에서 버전·EOL 정보를 추출한다(설계 4.2).
 * 구조화 출력(strict JSON 스키마)으로 스키마를 강제하고, 결과는 후보일 뿐 DB를 직접 바꾸지 않는다.
 */
@Component
class AiExtractor(private val props: EolProperties, private val json: JsonMapper) : Extractor {
    /**
     * OPENAI_BASE_URL(https://{리소스}.openai.azure.com/openai/v1 또는 같은 경로의 내부 게이트웨이)과 AZURE_OPENAI_KEY를 쓴다.
     * 내부 게이트웨이 도메인은 SDK가 Azure로 알아보지 못하므로 v1 경로 방식을 직접 지정한다(api-key 헤더, 모델은 본문의 배포 이름).
     * 키가 없는 환경(테스트)에서도 기동되도록 처음 호출할 때 만든다.
     */
    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder().fromEnv().azureUrlPathMode(AzureUrlPathMode.UNIFIED).build()
    }
    private val systemPrompt: String by lazy {
        ClassPathResource("prompts/${props.ai.promptVersion}.md").getContentAsString(Charsets.UTF_8)
    }

    /**
     * 출력 스키마는 직접 정의한다. Kotlin 클래스에서 자동 생성하면 nullable 필드도 null을 허용하지 않는 필수 값이 되어,
     * 문서에 없는 값을 AI가 지어내게 된다(STO-06 위반). strict 모드라 모든 필드가 required이고 없는 값은 null 타입으로 받는다.
     */
    private val responseFormat: ResponseFormatJsonSchema by lazy {
        val text = ClassPathResource("prompts/${props.ai.promptVersion}.schema.json").getContentAsString(Charsets.UTF_8)
        val map = json.readValue(text, Map::class.java)
        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
            .additionalProperties(map.entries.associate { (k, v) -> k as String to JsonValue.from(v) })
            .build()
        ResponseFormatJsonSchema.builder()
            .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder().name("extraction").strict(true).schema(schema).build())
            .build()
    }

    override fun extract(request: ExtractionRequest): Extraction {
        val params = ChatCompletionCreateParams.builder()
            .model(props.ai.model)
            .maxCompletionTokens(props.ai.maxTokens)
            .addSystemMessage(systemPrompt)
            .addUserMessage(userMessage(request))
            .responseFormat(responseFormat)
            // 추론 모델(o 시리즈, GPT-5 등)에만 보낸다. 그 밖의 모델은 이 값을 받으면 오류를 낸다.
            .apply { props.ai.effort?.takeIf { it.isNotBlank() }?.let { reasoningEffort(ReasoningEffort.of(it)) } }
            .build()

        val completion = try {
            client.chat().completions().create(params)
        } catch (e: Exception) {
            throw ExtractionException("AI_ERROR", "AI 호출 실패: ${e.message}", e)
        }
        val choice = completion.choices().firstOrNull() ?: throw ExtractionException("AI_ERROR", "AI 응답에 결과가 없음")
        when (choice.finishReason()) {
            ChatCompletion.Choice.FinishReason.LENGTH ->
                throw ExtractionException("AI_OUTPUT_TRUNCATED", "AI 출력이 max_completion_tokens(${props.ai.maxTokens})에서 잘림")
            ChatCompletion.Choice.FinishReason.CONTENT_FILTER ->
                throw ExtractionException("AI_REFUSED", "Azure 콘텐츠 필터가 응답을 막음")
            else -> Unit
        }
        choice.message().refusal().ifPresent { throw ExtractionException("AI_REFUSED", "AI가 요청을 거절함: $it") }
        val text = choice.message().content().orElse("")
        val result = try {
            json.readValue(text, ExtractionResult::class.java)
        } catch (e: Exception) {
            throw ExtractionException("AI_ERROR", "AI 응답이 스키마와 맞지 않음: ${e.message}", e)
        }
        val usage = completion.usage().orElse(null)
        return Extraction(result, completion.model(), usage?.promptTokens() ?: 0, usage?.completionTokens() ?: 0)
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
