package cloud.jjoon.eolpoc.collection.api

data class CollectRequest(val sourceId: Long? = null)

data class CollectResponse(val collectionIds: List<Long>)

data class CollectionContent(val rawContent: String?, val contentText: String?)
