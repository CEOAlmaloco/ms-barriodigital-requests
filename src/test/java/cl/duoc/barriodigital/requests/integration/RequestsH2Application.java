package cl.duoc.barriodigital.requests.integration;

import cl.duoc.barriodigital.requests.config.RabbitConfig;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.web.RequestController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Arranque exclusivo del classpath de test. No incorpora wallet ni H2 al JAR productivo. */
@TestConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = MunicipalRequest.class)
@EnableJpaRepositories(basePackageClasses = MunicipalRequestRepository.class)
@Import({
        RequestController.class,
        RequestService.class,
        RequestActivityService.class,
        ImageAttachmentSanitizer.class,
        RabbitConfig.class,
        EventPublisher.class
})
public class RequestsH2Application {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RequestsH2Application.class);
        application.setAdditionalProfiles("h2-integration");
        application.run(args);
    }
}
