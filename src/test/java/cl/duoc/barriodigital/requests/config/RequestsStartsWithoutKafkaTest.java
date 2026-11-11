package cl.duoc.barriodigital.requests.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.integration.RequestsH2Application;
import cl.duoc.barriodigital.requests.messaging.OutboxCleanup;
import cl.duoc.barriodigital.requests.messaging.OutboxHealthIndicator;
import cl.duoc.barriodigital.requests.messaging.OutboxRelay;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = {RequestsH2Application.class, KafkaConfig.class, SchedulingConfig.class, OutboxRelay.class,
        OutboxCleanup.class, OutboxHealthIndicator.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:ep332-sin-kafka;DB_CLOSE_DELAY=-1",
                "spring.kafka.bootstrap-servers=203.0.113.1:9092",
                "spring.kafka.producer.properties.max.block.ms=500",
                "barriodigital.outbox.scheduling.enabled=true",
                "barriodigital.outbox.relay-delay-ms=200",
                "management.health.rabbit.enabled=false",
                "management.endpoint.health.show-details=always"
        })
@ActiveProfiles("h2-integration")
@DirtiesContext
class RequestsStartsWithoutKafkaTest {

    private static final String REQ = "eeeeeeee-0000-4000-8000-000000000005";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TestRestTemplate http;
    @Autowired OutboxEventRepository outbox;
    @Autowired ApplicationContext context;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logs.add(event.getFormattedMessage());
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(OutboxRelay.class)).addAppender(appender);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(OutboxRelay.class)).detachAppender(appender);
        appender.stop();
    }

    @Test
    void arrancaRespondeYLosEventosEsperanEnElOutbox() throws Exception {
        String eventId = UUID.randomUUID().toString();
        outbox.save(new OutboxEvent(eventId, REQ, "request.created",
                "{\"eventId\":\"" + eventId + "\",\"traceId\":\"" + UUID.randomUUID() + "\",\"payload\":{}}",
                Instant.now()));

        OutboxEvent waiting = awaitAttempt(eventId);

        assertNull(waiting.getPublishedAt());
        assertTrue(waiting.getLastError() != null && !waiting.getLastError().isBlank());
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("relay-detenido sequence=")
                && line.contains("eventId=" + eventId)), logs.toString());

        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode(), health.getBody());
        JsonNode body = JSON.readTree(health.getBody());
        assertEquals("UP", body.get("status").asText());
        JsonNode component = body.at("/components/outbox");
        assertEquals("UP", component.get("status").asText());
        assertEquals(1, component.at("/details/pendientes").asLong());
        assertTrue(component.at("/details/antiguedadMasViejoSegundos").isIntegralNumber());

        assertEquals(HttpStatus.OK,
                http.getForEntity("/api/requests/meta/transitions", String.class).getStatusCode());
    }

    @Test
    void noDeclaraTopicosYNoUsaElListenerQueVuelcaLosMensajes() {
        assertTrue(context.getBeansOfType(org.apache.kafka.clients.admin.NewTopic.class).isEmpty());
        assertTrue(context.getBeansOfType(KafkaAdmin.NewTopics.class).isEmpty());
        assertEquals(1, context.getBeansOfType(org.springframework.kafka.support.ProducerListener.class).size());
        assertTrue(context.getBeansOfType(LoggingProducerListener.class).isEmpty());
        assertEquals("all", String.valueOf(kafkaTemplate.getProducerFactory().getConfigurationProperties()
                .get("acks")));
    }

    private OutboxEvent awaitAttempt(String eventId) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            OutboxEvent event = outbox.findById(eventId).orElseThrow();
            if (event.getAttempts() > 0) {
                return event;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("El relay no intento publicar en 20 s");
    }
}
