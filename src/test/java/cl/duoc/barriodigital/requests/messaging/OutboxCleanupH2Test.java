package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OutboxCleanupH2Test {

    private static final Instant NOW = Instant.parse("2026-11-11T06:30:00Z");
    private static final String REQ = "cccccccc-0000-4000-8000-000000000003";

    @Autowired OutboxEventRepository outbox;

    private OutboxCleanup cleanup;

    @BeforeEach
    void prepare() {
        outbox.deleteAll();
        cleanup = new OutboxCleanup(outbox, new MutableClock(NOW));
    }

    @Test
    void borraSoloLosPublicadosConMasDeSieteDias() {
        String old = published(NOW.minus(Duration.ofDays(8)));
        String justOverLimit = published(NOW.minus(Duration.ofDays(7)).minusMillis(1));
        String atLimit = published(NOW.minus(Duration.ofDays(7)));
        String recent = published(NOW.minus(Duration.ofDays(6)));
        String pendingOld = pending(NOW.minus(Duration.ofDays(40)));
        String failingOld = pending(NOW.minus(Duration.ofDays(30)));
        outbox.markFailed(failingOld, "broker caido");

        assertEquals(2, cleanup.purgePublished());

        assertFalse(outbox.existsById(old));
        assertFalse(outbox.existsById(justOverLimit));
        assertTrue(outbox.existsById(atLimit), "exactamente 7 dias no es mas de 7 dias");
        assertTrue(outbox.existsById(recent));
        assertTrue(outbox.existsById(pendingOld), "un pendiente no se borra nunca");
        assertTrue(outbox.existsById(failingOld), "un pendiente con fallas tampoco");
        assertEquals(2, outbox.countByPublishedAtIsNull());

        assertEquals(0, cleanup.purgePublished());
    }

    @Test
    void sinNadaQueBorrarNoFalla() {
        assertEquals(0, cleanup.purgePublished());
    }

    @Test
    void corrALas0330DeChileUnaVezAlDiaTambienEnLosCambiosDeHora() throws Exception {
        Scheduled scheduled = OutboxCleanup.class.getMethod("purgePublished").getAnnotation(Scheduled.class);
        assertEquals("0 30 3 * * *", scheduled.cron());
        assertEquals("America/Santiago", scheduled.zone());

        ZoneId chile = ZoneId.of(OutboxCleanup.ZONE);
        CronExpression cron = CronExpression.parse(OutboxCleanup.CRON);
        ZonedDateTime from = ZonedDateTime.of(2025, 12, 31, 12, 0, 0, 0, chile);
        Set<LocalDate> days = new HashSet<>();
        ZonedDateTime next = cron.next(from);
        int runs = 0;
        while (next.getYear() == 2026) {
            assertEquals(LocalTime.of(3, 30), next.toLocalTime(), next.toString());
            assertTrue(days.add(next.toLocalDate()), "dos ejecuciones el " + next.toLocalDate());
            runs++;
            next = cron.next(next);
        }
        assertEquals(LocalDate.of(2026, 1, 1).lengthOfYear(), runs);
        List<ZoneOffsetTransition> changes = new ArrayList<>();
        ZoneOffsetTransition change = chile.getRules().nextTransition(from.toInstant());
        while (change != null && change.getInstant().atZone(chile).getYear() == 2026) {
            changes.add(change);
            LocalTime local = change.getDateTimeBefore().toLocalTime();
            assertFalse(local.isAfter(LocalTime.of(2, 0)) && local.isBefore(LocalTime.of(5, 0)), change.toString());
            change = chile.getRules().nextTransition(change.getInstant());
        }
        assertEquals(2, changes.size(), changes.toString());
    }

    private String published(Instant publishedAt) {
        OutboxEvent event = outbox.save(OutboxFixtures.fresh(REQ));
        outbox.markPublished(event.getEventId(), publishedAt);
        return event.getEventId();
    }

    private String pending(Instant createdAt) {
        String eventId = UUID.randomUUID().toString();
        outbox.save(new OutboxEvent(eventId, REQ, "request.created", OutboxFixtures.envelope(eventId, REQ), createdAt));
        return eventId;
    }
}
