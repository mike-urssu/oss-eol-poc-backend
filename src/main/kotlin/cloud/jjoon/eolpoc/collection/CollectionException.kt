package cloud.jjoon.eolpoc.collection

class CollectionException(val code: String, message: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
