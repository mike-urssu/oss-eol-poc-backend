package cloud.jjoon.eolpoc.extraction

class ExtractionException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
