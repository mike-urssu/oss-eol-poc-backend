package cloud.jjoon.eolpoc.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** 공식 출처를 흉내 내는 로컬 HTTP 서버. 응답 본문과 상태 코드를 테스트에서 바꾼다. */
object FixtureServer {
    @Volatile var body: String = ""
    @Volatile var status: Int = 200
    @Volatile var contentType: String = "text/html; charset=utf-8"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    fun url(path: String = "/versions") = "http://127.0.0.1:${server.address.port}$path"

    fun reset() {
        body = ""
        status = 200
        contentType = "text/html; charset=utf-8"
    }
}
