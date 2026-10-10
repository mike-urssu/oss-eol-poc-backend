package cloud.jjoon.eolpoc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EolPocBackendApplication

fun main(args: Array<String>) {
    runApplication<EolPocBackendApplication>(*args)
}
