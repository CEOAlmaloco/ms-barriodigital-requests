package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OutboxHealthIndicatorH2Test {

    private static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
    private static final String REQ = "dddddddd-0000-4000-8000-000000000004";

    @Autowired OutboxEventRepository outbox;

    private OutboxHealthIndicator health;

    @BeforeEach
    void prepare() {
        outbox.deleteAll();
        health = new OutboxHealthIndicator(outbox, new MutableClock(NOW));
    }

    @Test
    void sinPendientesEstaUpConCeros() {
        Health result = health.health();
        assertEquals(Status.UP, result.getStatus());
        assertEquals(Map.of("pendientes", 0L, "antiguedadMasViejoSegundos", 0L), result.getDetails());
    }

    @Test
    void cuentaSoloPendientesYMideAlMasViejo() {
        save(NOW.minusSeconds(37));
        save(NOW.minusSeconds(5));
        String published = save(NOW.minus(Duration.ofHours(3)));
        outbox.markPublished(published, NOW.minus(Duration.ofHours(2)));

        Health result = health.health();

        assertEquals(Status.UP, result.getStatus());
        assertEquals(2L, result.getDetails().get("pendientes"));
        assertEquals(37L, result.getDetails().get("antiguedadMasViejoSegundos"));
    }

    @Test
    void conPendientesSigueUpAunqueKafkaEsteCaido() {
        String failing = save(NOW.minus(Duration.ofMinutes(10)));
        outbox.markFailed(failing, "broker caido");

        Health result = health.health();

        assertEquals(Status.UP, result.getStatus());
        assertEquals(600L, result.getDetails().get("antiguedadMasViejoSegundos"));
    }

    @Test
    void siNoPuedeLeerLaTablaRespondeUnknownYNoBajaElEstadoGeneral() {
        OutboxEventRepository broken = mock(OutboxEventRepository.class);
        when(broken.countByPublishedAtIsNull()).thenThrow(new DataAccessResourceFailureException("ORA-17002"));

        Health result = new OutboxHealthIndicator(broken, new MutableClock(NOW)).health();

        assertEquals(Status.UNKNOWN, result.getStatus());
        assertEquals(Map.of("error", "no se pudo leer outbox_events"), result.getDetails());
        assertEquals(Status.UP, new SimpleStatusAggregator().getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)));
    }

    private String save(Instant createdAt) {
        String eventId = UUID.randomUUID().toString();
        outbox.save(new OutboxEvent(eventId, REQ, "request.created", OutboxFixtures.envelope(eventId, REQ), createdAt));
        return eventId;
    }
}
