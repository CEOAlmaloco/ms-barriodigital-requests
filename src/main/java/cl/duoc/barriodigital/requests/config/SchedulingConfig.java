package cl.duoc.barriodigital.requests.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Programa el relay y la limpieza del outbox. Los tests lo apagan. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "barriodigital.outbox.scheduling", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
