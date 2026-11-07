package cl.duoc.barriodigital.requests.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Reloj del servicio. Los tests lo reemplazan por uno fijo para probar bordes como el cambio de año. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
