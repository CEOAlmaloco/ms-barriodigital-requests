package cl.duoc.barriodigital.requests.messaging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OutboxRelayH2Test {

    private static final String TOPIC = "requests.events";
    private static final String REQ_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String REQ_B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired OutboxEventRepository outbox;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T15:00:00Z"));
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> appender;
    private OutboxRelay relay;

    @BeforeEach
    void prepare() {
        outbox.deleteAll();
        relay = new OutboxRelay(outbox, kafka, clock, TOPIC, Duration.ofSeconds(2));
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
    void publicaLosPendientesEnOrdenDeSequenceConKeyRequestIdYLosMarca() throws Exception {
        OutboxEvent a1 = outbox.save(OutboxFixtures.fresh(REQ_A));
        OutboxEvent b1 = outbox.save(OutboxFixtures.fresh(REQ_B));
        OutboxEvent a2 = outbox.save(OutboxFixtures.fresh(REQ_A));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(3)).send(org.mockito.ArgumentMatchers.eq(TOPIC), keys.capture(), values.capture());
        assertEquals(List.of(REQ_A, REQ_B, REQ_A), keys.getAllValues());
        List<String> eventIds = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();
        for (String value : values.getAllValues()) {
            eventIds.add(JSON.readTree(value).get("eventId").asText());
            sequences.add(JSON.readTree(value).get("sequence").asLong());
        }
        assertEquals(List.of(a1.getEventId(), b1.getEventId(), a2.getEventId()), eventIds);
        assertEquals(List.of(a1.getEventSequence(), b1.getEventSequence(), a2.getEventSequence()), sequences);
        assertTrue(sequences.get(0) < sequences.get(1) && sequences.get(1) < sequences.get(2));

        for (OutboxEvent event : outbox.findAll()) {
            assertEquals(clock.instant(), event.getPublishedAt());
            assertEquals(0, event.getAttempts());
            assertTrue(!JSON.readTree(event.getPayload()).has("sequence"));
        }
        assertEquals(0, outbox.countByPublishedAtIsNull());

        relay.publishPending();
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
    }

    @Test
    void unaFallaDetieneElLoteYAlVolverKafkaSaleTodoEnOrden() throws Exception {
        OutboxEvent first = outbox.save(OutboxFixtures.fresh(REQ_A));
        OutboxEvent failing = outbox.save(OutboxFixtures.fresh(REQ_B));
        OutboxEvent after = outbox.save(OutboxFixtures.fresh(REQ_A));
        List<String> sent = new CopyOnWriteArrayList<>();
        boolean[] kafkaDown = {true};
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String eventId = JSON.readTree((String) invocation.getArgument(2)).get("eventId").asText();
            if (kafkaDown[0] && eventId.equals(failing.getEventId())) {
                return CompletableFuture.failedFuture(new KafkaException("Send failed",
                        new org.apache.kafka.common.errors.TimeoutException(
                                "Topic requests.events not present in metadata after 5000 ms.")));
            }
            sent.add(eventId);
            return CompletableFuture.completedFuture(null);
        });

        relay.publishPending();

        assertEquals(List.of(first.getEventId()), sent);
        OutboxEvent stuck = outbox.findById(failing.getEventId()).orElseThrow();
        assertNull(stuck.getPublishedAt());
        assertEquals(1, stuck.getAttempts());
        assertEquals("Topic requests.events not present in metadata after 5000 ms.", stuck.getLastError());
        assertNull(outbox.findById(after.getEventId()).orElseThrow().getPublishedAt());
        assertEquals(0, outbox.findById(after.getEventId()).orElseThrow().getAttempts());
        assertEquals(2, outbox.countByPublishedAtIsNull());
        assertTrue(logs.contains("relay-detenido sequence=" + failing.getEventSequence() + " eventId="
                + failing.getEventId() + " motivo=\"Topic requests.events not present in metadata after 5000 ms.\""));

        kafkaDown[0] = false;
        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();

        assertEquals(List.of(first.getEventId(), failing.getEventId(), after.getEventId()), sent);
        assertEquals(0, outbox.countByPublishedAtIsNull());
        OutboxEvent recovered = outbox.findById(failing.getEventId()).orElseThrow();
        assertEquals(1, recovered.getAttempts());
        assertEquals(clock.instant(), recovered.getPublishedAt());
        assertTrue(logs.contains("relay-reanudado pendientes=1"), logs.toString());
    }
}
