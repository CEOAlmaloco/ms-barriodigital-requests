package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

final class OutboxFixtures {

    static final Instant CREATED_AT = Instant.parse("2026-11-11T12:00:00Z");

    private OutboxFixtures() {
    }

    static String envelope(String eventId, String requestId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"request.status-changed\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-11-11T12:00:00.123456Z\",\"producer\":\"requests\","
                + "\"traceId\":\"" + UUID.nameUUIDFromBytes(eventId.getBytes()) + "\","
                + "\"actor\":{\"id\":\"oid-ana\",\"name\":\"Ana\",\"role\":\"Funcionario\"},"
                + "\"payload\":{\"requestId\":\"" + requestId + "\",\"folio\":\"BD-2026-000001\","
                + "\"procedureType\":\"bache\",\"sector\":\"5\",\"fromStatus\":\"INGRESADO\","
                + "\"toStatus\":\"ADMITIDO\",\"rejectionReason\":null,\"interventionReason\":null,"
                + "\"latitude\":-33.4372561,\"longitude\":-70.6506489}}";
    }

    static OutboxEvent fresh(String requestId) {
        String eventId = UUID.randomUUID().toString();
        return new OutboxEvent(eventId, requestId, "request.status-changed", envelope(eventId, requestId), CREATED_AT);
    }

    static OutboxEvent stored(long sequence, String requestId) {
        OutboxEvent event = fresh(requestId);
        ReflectionTestUtils.setField(event, "eventSequence", sequence);
        return event;
    }
}
