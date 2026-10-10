package cloud.jjoon.eolpoc.common

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** ResponseStatusException은 spring.mvc.problemdetails로 RFC 9457 응답이 된다. */
fun notFound(message: String) = ResponseStatusException(HttpStatus.NOT_FOUND, message)

fun conflict(message: String) = ResponseStatusException(HttpStatus.CONFLICT, message)

fun badRequest(message: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, message)
