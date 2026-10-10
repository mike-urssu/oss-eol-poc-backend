package cloud.jjoon.eolpoc.common

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan
class AppConfig(private val props: EolProperties) : WebMvcConfigurer {

    /** 기준일(오늘)은 eol.zone 기준으로 계산한다. */
    @Bean
    fun clock(): Clock = Clock.system(props.zone)

    /** 수동 재수집을 요청 스레드와 분리해 실행한다(202 즉시 응답). */
    @Bean
    fun collectionExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**").allowedOrigins(*props.cors.allowedOrigins.toTypedArray()).allowedMethods("*")
    }
}
