package cl.duoc.barriodigital.requests.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre del contrato EP2-01. {@code correlationId} es el id del trámite.
 */
public record EventEnvelope(
        String type,
        UUID eventId,
        Instant timestamp,
        UUID traceId,
        String correlationId,
        Object payload
) {
}
