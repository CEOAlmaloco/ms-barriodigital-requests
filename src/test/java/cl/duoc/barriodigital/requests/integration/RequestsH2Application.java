package cl.duoc.barriodigital.requests.integration;

import cl.duoc.barriodigital.requests.infrastructure.config.ClockConfig;
import cl.duoc.barriodigital.requests.infrastructure.config.RabbitConfig;
import cl.duoc.barriodigital.requests.infrastructure.config.WebConfig;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.adapter.out.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.adapter.out.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.application.usecase.FolioCounterService;
import cl.duoc.barriodigital.requests.application.usecase.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.application.usecase.RequestActivityService;
import cl.duoc.barriodigital.requests.application.usecase.RequestAssignmentService;
import cl.duoc.barriodigital.requests.application.usecase.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.application.usecase.RequestService;
import cl.duoc.barriodigital.requests.application.usecase.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.application.usecase.StaffMemberService;
import cl.duoc.barriodigital.requests.adapter.in.web.RequestController;
import cl.duoc.barriodigital.requests.adapter.in.web.RequestsExceptionHandler;
import cl.duoc.barriodigital.requests.adapter.in.web.SingleRoleInterceptor;
import cl.duoc.barriodigital.requests.adapter.in.web.StaffMemberInterceptor;
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
        RequestsExceptionHandler.class,
        RequestService.class,
        RequestCreationOrchestrationService.class,
        FolioCounterService.class,
        ClockConfig.class,
        WebConfig.class,
        SingleRoleInterceptor.class,
        StaffMemberService.class,
        RequestAssignmentService.class,
        StaffMemberInterceptor.class,
        RequestActivityService.class,
        ImageAttachmentSanitizer.class,
        RabbitConfig.class,
        EventPublisher.class,
        DomainEventRecorder.class,
        NotificationPreferenceService.class
})
public class RequestsH2Application {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RequestsH2Application.class);
        application.setAdditionalProfiles("h2-integration");
        application.run(args);
    }
}
