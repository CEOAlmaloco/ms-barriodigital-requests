package cl.duoc.barriodigital.requests.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DuplicateCheckProperties.class)
public class DuplicateCheckConfig {
}
