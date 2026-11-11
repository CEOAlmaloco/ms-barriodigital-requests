package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.config.KafkaNamesProperties;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica los pendientes del outbox en requests.events, en orden de event_sequence y con key requestId.
 * Ante cualquier falla detiene el lote para no adelantar eventos. Asume una sola instancia de requests.
 */
@Component
public class OutboxRelay {

    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    static final int LAST_ERROR_MAX_BYTES = 500;
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30));

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String topic;
    private final Duration ackTimeout;

    private int failedBatches;
    private Instant retryAt;

    @Autowired
    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, Clock clock,
                       KafkaNamesProperties names) {
        this(outbox, kafka, clock, names.topic().requestsEvents(), ACK_TIMEOUT);
    }

    OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, Clock clock, String topic,
                Duration ackTimeout) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    @Scheduled(fixedDelayString = "${barriodigital.outbox.relay-delay-ms:1000}")
    public synchronized void publishPending() {
        if (retryAt != null && clock.instant().isBefore(retryAt)) {
            return;
        }
        List<OutboxEvent> pending;
        try {
            pending = outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc();
        } catch (RuntimeException ex) {
            halt(null, "no se pudo leer outbox_events: " + reason(ex));
            return;
        }
        for (OutboxEvent event : pending) {
            try {
                send(event);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String motivo = reason(ex);
                recordFailure(event, motivo);
                halt(event, motivo);
                return;
            }
            try {
                outbox.markPublished(event.getEventId(), clock.instant());
            } catch (RuntimeException ex) {
                // Kafka ya lo tiene: queda pendiente y se reenvía primero; el duplicado se descarta por eventId.
                halt(event, "Kafka confirmo pero no se pudo marcar published_at: " + reason(ex));
                return;
            }
            resume();
        }
    }

    private void send(OutboxEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        String json = withSequence(event.getPayload(), event.getEventSequence());
        kafka.send(topic, event.getAggregateId(), json).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordFailure(OutboxEvent event, String motivo) {
        try {
            outbox.markFailed(event.getEventId(), truncateUtf8(motivo, LAST_ERROR_MAX_BYTES));
        } catch (RuntimeException ex) {
            log.warn("relay-falla-sin-registrar eventId={} motivo=\"{}\"", event.getEventId(), oneLine(reason(ex)));
        }
    }

    private void halt(OutboxEvent event, String motivo) {
        failedBatches++;
        Duration wait = backoff(failedBatches);
        boolean first = retryAt == null;
        retryAt = clock.instant().plus(wait);
        if (!first) {
            return;
        }
        if (event == null) {
            log.error("relay-detenido motivo=\"{}\"", oneLine(motivo));
        } else {
            log.error("relay-detenido sequence={} eventId={} motivo=\"{}\"",
                    event.getEventSequence(), event.getEventId(), oneLine(motivo));
        }
    }

    private void resume() {
        if (retryAt == null) {
            return;
        }
        failedBatches = 0;
        retryAt = null;
        String pendientes;
        try {
            pendientes = String.valueOf(outbox.countByPublishedAtIsNull());
        } catch (RuntimeException ex) {
            pendientes = "desconocido";
        }
        log.info("relay-reanudado pendientes={}", pendientes);
    }

    static Duration backoff(int failedBatches) {
        return BACKOFF.get(Math.min(Math.max(failedBatches, 1), BACKOFF.size()) - 1);
    }

    /** Agrega sequence al sobre, después de traceId. */
    static String withSequence(String payload, Long sequence) {
        if (sequence == null) {
            throw new IllegalStateException("El evento no tiene event_sequence");
        }
        try {
            JsonNode node = DomainEventRecorder.JSON.readTree(payload);
            if (!(node instanceof ObjectNode source)) {
                throw new IllegalStateException("El payload del outbox no es un objeto JSON");
            }
            ObjectNode envelope = DomainEventRecorder.JSON.createObjectNode();
            boolean placed = false;
            for (Iterator<Map.Entry<String, JsonNode>> it = source.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if ("sequence".equals(field.getKey())) {
                    continue;
                }
                envelope.set(field.getKey(), field.getValue());
                if ("traceId".equals(field.getKey())) {
                    envelope.put("sequence", sequence);
                    placed = true;
                }
            }
            if (!placed) {
                envelope.put("sequence", sequence);
            }
            return DomainEventRecorder.JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("El payload del outbox no es JSON valido", ex);
        }
    }

    /** Mensaje de la causa más profunda. */
    String reason(Throwable ex) {
        if (ex instanceof TimeoutException) {
            return "sin confirmacion del broker en " + ackTimeout.toMillis() + " ms";
        }
        String message = null;
        for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        return message != null ? message : ex.getClass().getSimpleName();
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").replace('"', '\'').trim();
    }

    /** last_error es VARCHAR2(500) en bytes: recorta sin partir caracteres. */
    static String truncateUtf8(String text, int maxBytes) {
        String clean = oneLine(text);
        if (clean.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return clean;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < clean.length(); ) {
            int codePoint = clean.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            out.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
