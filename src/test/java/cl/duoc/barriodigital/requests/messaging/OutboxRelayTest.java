package cl.duoc.barriodigital.requests.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final String TOPIC = "requests.events";
    private static final String REQ_A = "11111111-1111-1111-1111-111111111111";
    private static final String REQ_B = "22222222-2222-2222-2222-222222222222";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T12:00:10Z"));
    private final List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> appender;
    private OutboxRelay relay;

    @BeforeEach
    void prepare() {
        relay = new OutboxRelay(outbox, kafka, clock, TOPIC, Duration.ofMillis(200));
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logs.add(event);
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
    void siOracleNoMarcaTrasLaConfirmacionDetieneElLoteYReenviaEseEventoPrimero() {
        OutboxEvent first = OutboxFixtures.stored(1, REQ_A);
        OutboxEvent second = OutboxFixtures.stored(2, REQ_A);
        OutboxEvent third = OutboxFixtures.stored(3, REQ_B);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc())
                .thenReturn(List.of(first, second, third))
                .thenReturn(List.of(second, third));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(confirmed());
        when(outbox.markPublished(eq(second.getEventId()), any()))
                .thenThrow(new DataAccessResourceFailureException("ORA-17002: Error de E/S: conexion cerrada"))
                .thenReturn(1);
        when(outbox.markPublished(eq(first.getEventId()), any())).thenReturn(1);
        when(outbox.markPublished(eq(third.getEventId()), any())).thenReturn(1);
        when(outbox.countByPublishedAtIsNull()).thenReturn(0L);

        relay.publishPending();

        verify(kafka, times(1)).send(eq(TOPIC), eq(REQ_A), contains(first.getEventId()));
        verify(kafka, times(1)).send(eq(TOPIC), eq(REQ_A), contains(second.getEventId()));
        verify(kafka, never()).send(eq(TOPIC), eq(REQ_B), anyString());
        verify(outbox, never()).markFailed(anyString(), anyString());
        assertEquals(1, errors("relay-detenido").size());
        String message = errors("relay-detenido").get(0).getFormattedMessage();
        assertTrue(message.contains("sequence=2 eventId=" + second.getEventId()), message);
        assertTrue(message.contains("no se pudo marcar published_at: ORA-17002"), message);

        relay.publishPending();
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());

        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();

        InOrder order = inOrder(kafka);
        order.verify(kafka).send(eq(TOPIC), eq(REQ_A), contains(first.getEventId()));
        order.verify(kafka, times(2)).send(eq(TOPIC), eq(REQ_A), contains(second.getEventId()));
        order.verify(kafka).send(eq(TOPIC), eq(REQ_B), contains(third.getEventId()));
        assertTrue(infos("relay-reanudado pendientes=0"));
    }

    @Test
    void unEnvioFallidoSumaAttemptsGuardaElMotivoYDetieneElLote() {
        OutboxEvent first = OutboxFixtures.stored(7, REQ_A);
        OutboxEvent second = OutboxFixtures.stored(8, REQ_B);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc()).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new KafkaException("Send failed",
                        new TimeoutException("Topic requests.events not present in metadata after 5000 ms."))));

        relay.publishPending();

        verify(outbox).markFailed(first.getEventId(), "Topic requests.events not present in metadata after 5000 ms.");
        verify(outbox, never()).markPublished(anyString(), any());
        verify(kafka, never()).send(eq(TOPIC), eq(REQ_B), anyString());
        assertEquals("relay-detenido sequence=7 eventId=" + first.getEventId()
                        + " motivo=\"Topic requests.events not present in metadata after 5000 ms.\"",
                errors("relay-detenido").get(0).getFormattedMessage());
    }

    @Test
    void unaConfirmacionQueNoLlegaCuentaComoFalla() {
        OutboxEvent event = OutboxFixtures.stored(1, REQ_A);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc()).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        relay.publishPending();

        verify(outbox).markFailed(event.getEventId(), "sin confirmacion del broker en 200 ms");
        verify(outbox, never()).markPublished(anyString(), any());
    }

    @Test
    void siOracleTampocoRegistraLaFallaIgualDetieneElLote() {
        OutboxEvent first = OutboxFixtures.stored(1, REQ_A);
        OutboxEvent second = OutboxFixtures.stored(2, REQ_A);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc()).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker caido")));
        when(outbox.markFailed(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("ORA-17002"));

        relay.publishPending();

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        assertEquals(1, errors("relay-detenido").size());
        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("relay-falla-sin-registrar eventId=" + first.getEventId())));
    }

    @Test
    void siNoPuedeLeerElOutboxNoEnviaYEsperaAntesDeReintentar() {
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc())
                .thenThrow(new DataAccessResourceFailureException("ORA-12541: no listener"))
                .thenReturn(List.of());

        relay.publishPending();
        relay.publishPending();

        verify(outbox, times(1)).findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        assertEquals("relay-detenido motivo=\"no se pudo leer outbox_events: ORA-12541: no listener\"",
                errors("relay-detenido").get(0).getFormattedMessage());

        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();
        verify(outbox, times(2)).findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc();
    }

    @Test
    void laEsperaCreceUnoDosCuatroOchoDieciseisYLuegoTreinta() {
        List<Long> seconds = new ArrayList<>();
        for (int failures = 1; failures <= 8; failures++) {
            seconds.add(OutboxRelay.backoff(failures).toSeconds());
        }
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), seconds);
    }

    @Test
    void conKafkaCaidoReintentaSegunLaEsperaYRegistraUnaSolaVezPorRacha() {
        OutboxEvent event = OutboxFixtures.stored(1, REQ_A);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc()).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("broker caido")));

        relay.publishPending();
        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();
        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());
        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        clock.advance(Duration.ofSeconds(3));
        relay.publishPending();
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        clock.advance(Duration.ofSeconds(1));
        relay.publishPending();
        verify(kafka, times(4)).send(anyString(), anyString(), anyString());

        verify(outbox, times(4)).markFailed(eq(event.getEventId()), eq("broker caido"));
        assertEquals(1, errors("relay-detenido").size());
        assertFalse(infos("relay-reanudado"));
    }

    @Test
    void elSobrePublicadoLlevaSequenceDespuesDeTraceIdYElRestoIntacto() throws Exception {
        OutboxEvent event = OutboxFixtures.stored(1057, REQ_A);

        String published = OutboxRelay.withSequence(event.getPayload(), 1057L);

        JsonNode original = JSON.readTree(event.getPayload());
        JsonNode node = JSON.readTree(published);
        assertEquals(1057, node.get("sequence").asLong());
        assertTrue(node.get("sequence").isIntegralNumber());
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "producer", "traceId",
                "sequence", "actor", "payload"), fields);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("sequence");
        assertEquals(original, node);
        assertTrue(published.contains("\"latitude\":-33.4372561,\"longitude\":-70.6506489"), published);
        assertTrue(published.contains("\"rejectionReason\":null"), published);
    }

    @Test
    void elMotivoSeRecortaAQuinientosBytesSinCortarUnCaracter() {
        String motivo = "é".repeat(300) + "\n\"fin\"";
        String cut = OutboxRelay.truncateUtf8(motivo, 500);
        assertEquals(500, cut.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("é".repeat(250), cut);
        assertEquals("corto 'sin' saltos", OutboxRelay.truncateUtf8("corto\n\"sin\"  saltos", 500));
        String emoji = "a" + "😀".repeat(200);
        String cutEmoji = OutboxRelay.truncateUtf8(emoji, 500);
        assertTrue(cutEmoji.getBytes(StandardCharsets.UTF_8).length <= 500);
        assertFalse(Character.isHighSurrogate(cutEmoji.charAt(cutEmoji.length() - 1)));
    }

    private static CompletableFuture<SendResult<String, String>> confirmed() {
        return CompletableFuture.completedFuture(null);
    }

    private static String contains(String text) {
        return org.mockito.ArgumentMatchers.contains(text);
    }

    private List<ILoggingEvent> errors(String prefix) {
        List<ILoggingEvent> found = new ArrayList<>();
        for (Iterator<ILoggingEvent> it = logs.iterator(); it.hasNext(); ) {
            ILoggingEvent event = it.next();
            if (event.getLevel() == Level.ERROR && event.getFormattedMessage().startsWith(prefix)) {
                found.add(event);
            }
        }
        return found;
    }

    private boolean infos(String prefix) {
        return logs.stream().anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().startsWith(prefix));
    }
}
