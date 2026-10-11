package cloud.jjoon.eolpoc.candidate.api

import cloud.jjoon.eolpoc.candidate.ReviewService
import cloud.jjoon.eolpoc.candidate.domain.Candidate
import cloud.jjoon.eolpoc.lifecycle.LifecycleQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/candidates")
class ReviewController(private val reviews: ReviewService, private val query: LifecycleQueryService) {
    @GetMapping
    fun pending() = query.pendingCandidates()

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long, @RequestBody(required = false) r: ReviewRequest?) =
        reviews.approve(id, r?.reviewer, r?.comment).toResponse()

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long, @RequestBody(required = false) r: ReviewRequest?) =
        reviews.reject(id, r?.reviewer, r?.comment).toResponse()

    private fun Candidate.toResponse() =
        ReviewResponse(id!!, version, validationStatus, validationReasons, reviewedBy, reviewComment, reviewedAt)
}
