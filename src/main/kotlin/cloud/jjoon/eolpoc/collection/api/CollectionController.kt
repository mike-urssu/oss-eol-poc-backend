package cloud.jjoon.eolpoc.collection.api

import cloud.jjoon.eolpoc.collection.CollectionOrchestrator
import cloud.jjoon.eolpoc.collection.domain.CollectionRunRepository
import cloud.jjoon.eolpoc.collection.domain.Trigger
import cloud.jjoon.eolpoc.common.notFound
import cloud.jjoon.eolpoc.lifecycle.LifecycleQueryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class CollectionController(
    private val orchestrator: CollectionOrchestrator,
    private val query: LifecycleQueryService,
    private val runs: CollectionRunRepository,
) {
    @PostMapping("/opensources/{slug}/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun collect(@PathVariable slug: String, @RequestBody(required = false) r: CollectRequest?) =
        CollectResponse(orchestrator.trigger(slug, r?.sourceId, Trigger.MANUAL))

    @GetMapping("/opensources/{slug}/collections")
    fun list(@PathVariable slug: String, @RequestParam(defaultValue = "20") limit: Int) = query.collections(slug, limit.coerceIn(1, 200))

    /** 근거 추적용 원본. 프런트엔드는 반드시 텍스트로 표시한다(NFR-07). */
    @GetMapping("/collections/{id}/content")
    fun content(@PathVariable id: Long): CollectionContent {
        val run = runs.findById(id).orElse(null) ?: throw notFound("수집을 찾을 수 없습니다: $id")
        return CollectionContent(run.rawContent, run.contentText)
    }
}
