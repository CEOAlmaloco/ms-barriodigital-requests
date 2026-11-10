package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Marca append-only del expediente. No tiene actualización ni borrado en la API.
 * El instante lo pone el servidor. El autor sale del header del BFF, no del body.
 */
@Entity
@Table(name = "request_activity", indexes = @Index(name = "idx_request_activity_request", columnList = "request_id"))
public class RequestActivity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityVisibility visibility;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    @Column(nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private RequestStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ContactChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_result", length = 40)
    private ContactResult result;

    @Column(length = 500)
    private String note;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /**
     * Motivo de una intervención del Admin (contrato EP3-04, sección 3). Es interno: el Vecino nunca lo recibe,
     * aunque la fila sea PUBLICO, y no entra en el summary.
     */
    @Column(name = "intervention_reason", length = 500)
    private String interventionReason;

    protected RequestActivity() {
        // JPA
    }

    private RequestActivity(
            String requestId,
            Instant occurredAt,
            ActivityKind kind,
            ActivityVisibility visibility,
            String actorId,
            String actorName,
            String summary
    ) {
        this.id = UUID.randomUUID().toString();
        this.requestId = requestId;
        this.occurredAt = occurredAt;
        this.kind = kind;
        this.visibility = visibility;
        this.actorId = actorId;
        this.actorName = actorName;
        this.summary = summary;
    }

    /** Alta del trámite. La ve el vecino. */
    public static RequestActivity created(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName
    ) {
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.CREADO,
                ActivityVisibility.PUBLICO,
                actorId,
                actorName,
                "Solicitud recibida."
        );
        event.toStatus = RequestStatus.INGRESADO;
        return event;
    }

    /**
     * Cambio de estado. Guarda el valor anterior y el nuevo. La ve el vecino.
     * El summary lleva solo el motivo de rechazo; el de intervención queda aparte, para staff.
     */
    public static RequestActivity statusChanged(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName,
            RequestStatus from,
            RequestStatus to,
            String reason,
            String interventionReason
    ) {
        String summary = "Pasó de " + from.label() + " a " + to.label() + ".";
        if (reason != null && !reason.isBlank()) {
            summary = summary + " Motivo: " + reason.trim();
        }
        if (summary.length() > 500) {
            summary = summary.substring(0, 500);
        }
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.ESTADO,
                ActivityVisibility.PUBLICO,
                actorId,
                actorName,
                summary
        );
        event.fromStatus = from;
        event.toStatus = to;
        event.reason = reason == null || reason.isBlank() ? null : reason.trim();
        event.interventionReason = blankToNull(interventionReason);
        return event;
    }

    /**
     * Gestión declarada por el funcionario. Queda interna.
     * No prueba que el vecino haya recibido la llamada o el mensaje.
     */
    public static RequestActivity gestion(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName,
            ContactChannel channel,
            ContactResult result,
            String note,
            Instant nextAttemptAt,
            String interventionReason
    ) {
        String summary = channel.label() + ". Resultado: " + result.label() + ".";
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.GESTION,
                ActivityVisibility.INTERNO,
                actorId,
                actorName,
                summary
        );
        event.channel = channel;
        event.result = result;
        event.note = note == null || note.isBlank() ? null : note.trim();
        event.nextAttemptAt = nextAttemptAt;
        event.interventionReason = blankToNull(interventionReason);
        return event;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ActivityKind getKind() {
        return kind;
    }

    public ActivityVisibility getVisibility() {
        return visibility;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getSummary() {
        return summary;
    }

    public RequestStatus getFromStatus() {
        return fromStatus;
    }

    public RequestStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public ContactChannel getChannel() {
        return channel;
    }

    public ContactResult getResult() {
        return result;
    }

    public String getNote() {
        return note;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getInterventionReason() {
        return interventionReason;
    }
}
