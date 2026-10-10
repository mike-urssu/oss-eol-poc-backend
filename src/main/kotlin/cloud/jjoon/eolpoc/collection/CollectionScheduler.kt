package cloud.jjoon.eolpoc.collection

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CollectionScheduler(private val orchestrator: CollectionOrchestrator) {
    @Scheduled(cron = "\${eol.collection.cron}", zone = "\${eol.zone}")
    fun collectDaily() = orchestrator.collectAllActive()
}
