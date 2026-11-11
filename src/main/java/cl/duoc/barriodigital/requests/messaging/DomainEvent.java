package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.RequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre de requests.events (contrato EP3-01, sección 4), tal como se guarda en el outbox: sin sequence, que lo
 * agrega el relay al publicar, y sin sourceIp, que es solo de audit.timeline.
 * No se unifica con EventEnvelope de RabbitMQ: los nombres distintos son deliberados (EP3-01, sección 4).
 * Los payloads tienen siempre los mismos campos, con null cuando no aplica (EP3-01, sección 5).
 */
public record DomainEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        UUID traceId,
        Actor actor,
        Object payload
) {

    /** Quien actuó. role es su único rol (EP3-04, sección 4). */
    public record Actor(String id, String name, String role) {
    }

    /** request.created. Sin correo ni dirección textual (EP3-01, sección 8). */
    public record Created(
            String requestId,
            String folio,
            String procedureType,
            String procedureLabel,
            String sector,
            Double latitude,
            Double longitude,
            RequestStatus status,
            Integer slaBusinessDays
    ) {
    }

    /** request.status-changed. reason solo en un rechazo; interventionReason solo si actuó el Admin. */
    public record StatusChanged(
            String requestId,
            String folio,
            String procedureType,
            String sector,
            RequestStatus fromStatus,
            RequestStatus toStatus,
            String reason,
            String interventionReason
    ) {
    }

    /** request.cancelled. Solo INGRESADO → CANCELADO por el titular (EP3-76). Sin motivo ni intervención. */
    public record Cancelled(
            String requestId,
            String folio,
            String procedureType,
            String sector,
            RequestStatus fromStatus,
            RequestStatus toStatus
    ) {
    }

    /** request.contact-logged. Sin la nota libre, que puede tener datos del contacto (EP3-01, sección 8). */
    public record ContactLogged(
            String requestId,
            String folio,
            ContactChannel channel,
            ContactResult result,
            Instant nextAttemptAt,
            String interventionReason
    ) {
    }

    /** request.assigned. previousAssigneeId y reason van null en la primera asignación. */
    public record Assigned(
            String requestId,
            String folio,
            String assigneeId,
            String assigneeName,
            String previousAssigneeId,
            String reason
    ) {
    }
}
