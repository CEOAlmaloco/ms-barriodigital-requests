package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.util.UUID;

/**
 * Marca append-only del expediente. No tiene actualización ni borrado en la API.
 * El instante lo pone el servidor. El autor sale del header del BFF, no del body.
 * Toda acción humana tiene autor: solo el hito público de asignación va sin actor (contrato EP3-05, sección 6.4).
 * En Oracle, la misma regla la crea db/assignment-request-activity.sql.
 */
@Entity
@Table(name = "request_activity", indexes = @Index(name = "idx_request_activity_request", columnList = "request_id"))
@Check(name = "ck_request_activity_actor",
        constraints = "actor_id IS NOT NULL OR (kind = 'ASIGNACION' AND visibility = 'PUBLICO')")
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

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    /** El único rol de quien actuó (EP3-04, sección 4). Null en el hito sin actor y en las filas anteriores a EP3-31. */
    @Column(name = "actor_role", length = 20)
    private String actorRole;

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

    /** Nuevo responsable. Solo en las filas ASIGNACION internas. */
    @Column(name = "assignee_id", length = 64)
    private String assigneeId;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    /** Responsable anterior. Null en la primera asignación. */
    @Column(name = "previous_assignee_id", length = 64)
    private String previousAssigneeId;

    @Column(name = "previous_assignee_name", length = 200)
    private String previousAssigneeName;

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
            String actorRole,
            String summary
    ) {
        this.id = UUID.randomUUID().toString();
        this.requestId = requestId;
        this.occurredAt = occurredAt;
        this.kind = kind;
        this.visibility = visibility;
        this.actorId = actorId;
        this.actorName = actorName;
        this.actorRole = actorRole;
        this.summary = summary;
    }

    /** Alta del trámite. La ve el vecino. */
    public static RequestActivity created(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName,
            String actorRole
    ) {
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.CREADO,
                ActivityVisibility.PUBLICO,
                actorId,
                actorName,
                actorRole,
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
            String actorRole,
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
                actorRole,
                summary
        );
        event.fromStatus = from;
        event.toStatus = to;
        event.reason = reason == null || reason.isBlank() ? null : reason.trim();
        event.interventionReason = blankToNull(interventionReason);
        return event;
    }

    /**
     * Encuesta de satisfacción del titular (EP3-74). La ve el vecino.
     * El score queda en reason como texto; el comentario opcional en note.
     */
    public static RequestActivity rated(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName,
            String actorRole,
            int score,
            String comment
    ) {
        String summary = "Calificó el trámite con " + score + ".";
        if (comment != null && !comment.isBlank()) {
            summary = summary + " Comentario: " + comment.trim();
        }
        if (summary.length() > 500) {
            summary = summary.substring(0, 500);
        }
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.CALIFICACION,
                ActivityVisibility.PUBLICO,
                actorId,
                actorName,
                actorRole,
                summary
        );
        event.reason = Integer.toString(score);
        event.note = blankToNull(comment);
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
            String actorRole,
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
                actorRole,
                summary
        );
        event.channel = channel;
        event.result = result;
        event.note = note == null || note.isBlank() ? null : note.trim();
        event.nextAttemptAt = nextAttemptAt;
        event.interventionReason = blankToNull(interventionReason);
        return event;
    }

    /**
     * Toma, asignación o reasignación. Queda interna, con el detalle para el staff (EP3-05, sección 6.1).
     * reason es el motivo de la reasignación, en la misma columna que el motivo de rechazo de las filas ESTADO.
     */
    public static RequestActivity assigned(
            String requestId,
            Instant occurredAt,
            String actorId,
            String actorName,
            String actorRole,
            String assigneeId,
            String assigneeName,
            String previousAssigneeId,
            String previousAssigneeName,
            String reason
    ) {
        String summary;
        if (previousAssigneeId == null && assigneeId.equals(actorId)) {
            summary = "Tomó el trámite";
        } else if (previousAssigneeId == null) {
            summary = "Asignó a " + assigneeName;
        } else {
            summary = "Reasignó de " + previousAssigneeName + " a " + assigneeName + ". Motivo: " + reason;
        }
        if (summary.length() > 500) {
            summary = summary.substring(0, 500);
        }
        RequestActivity event = new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.ASIGNACION,
                ActivityVisibility.INTERNO,
                actorId,
                actorName,
                actorRole,
                summary
        );
        event.assigneeId = assigneeId;
        event.assigneeName = assigneeName;
        event.previousAssigneeId = previousAssigneeId;
        event.previousAssigneeName = previousAssigneeName;
        event.reason = blankToNull(reason);
        return event;
    }

    /**
     * Hito público de la primera asignación, sin actor (EP3-04, sección 6.2). Es lo único que el Vecino ve
     * de la asignación; las reasignaciones no generan hito.
     */
    public static RequestActivity assignmentMilestone(String requestId, Instant occurredAt) {
        return new RequestActivity(
                requestId,
                occurredAt,
                ActivityKind.ASIGNACION,
                ActivityVisibility.PUBLICO,
                null,
                null,
                null,
                "Tu solicitud fue asignada para su atención"
        );
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

    public String getActorRole() {
        return actorRole;
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

    public String getAssigneeId() {
        return assigneeId;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public String getPreviousAssigneeId() {
        return previousAssigneeId;
    }

    public String getPreviousAssigneeName() {
        return previousAssigneeName;
    }

    /** El hito público de la primera asignación, que el staff no necesita: ya tiene la fila interna. */
    public boolean isAssignmentMilestone() {
        return kind == ActivityKind.ASIGNACION && visibility == ActivityVisibility.PUBLICO;
    }
}
