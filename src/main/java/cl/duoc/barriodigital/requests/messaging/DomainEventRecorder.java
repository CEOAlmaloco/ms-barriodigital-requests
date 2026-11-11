package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Registra los hechos del trámite en outbox_events (contrato EP3-02, secciones 2.2 y 2.4).
 * Exige la transacción del cambio: o quedan el trámite, su RequestActivity y el evento, o ninguno.
 * Cada evento sale de la fila del expediente que lo origina, así el instante, el actor y los datos coinciden.
 * No usa clases de Kafka: el relay (EP3-32) publica lo que queda aquí.
 */
@Component
public class DomainEventRecorder {

    public static final String REQUEST_CREATED = "request.created";
    public static final String REQUEST_STATUS_CHANGED = "request.status-changed";
    public static final String REQUEST_CONTACT_LOGGED = "request.contact-logged";
    public static final String REQUEST_ASSIGNED = "request.assigned";
    public static final String REQUEST_RATED = "request.rated";

    static final String PRODUCER = "requests";
    static final int SCHEMA_VERSION = 1;

    /** Propio y no el de Spring: el JSON es el contrato EP3-01 y no debe cambiar con la configuración global. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    private final OutboxEventRepository outbox;
    private final Clock clock;

    public DomainEventRecorder(OutboxEventRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Registra el evento que corresponde a la fila del expediente: CREADO, ESTADO, GESTION, CALIFICACION o la marca
     * interna de ASIGNACION. El hito público de asignación no genera evento (EP3-05, sección 7): una asignación es un evento.
     * traceId es el mismo de los comandos de RabbitMQ del cambio.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(MunicipalRequest request, RequestActivity row, UUID traceId) {
        Objects.requireNonNull(traceId, "traceId");
        String type;
        Object payload;
        switch (row.getKind()) {
            case CREADO -> {
                type = REQUEST_CREATED;
                payload = new DomainEvent.Created(request.getId(), request.getFolio(), request.getProcedureType(),
                        procedureLabel(request), request.getSector(), request.getLatitude(), request.getLongitude(),
                        RequestStatus.INGRESADO, request.getSlaBusinessDays());
            }
            case ESTADO -> {
                type = REQUEST_STATUS_CHANGED;
                payload = new DomainEvent.StatusChanged(request.getId(), request.getFolio(),
                        request.getProcedureType(), request.getSector(), row.getFromStatus(), row.getToStatus(),
                        row.getReason(), row.getInterventionReason());
            }
            case GESTION -> {
                type = REQUEST_CONTACT_LOGGED;
                payload = new DomainEvent.ContactLogged(request.getId(), request.getFolio(), row.getChannel(),
                        row.getResult(), row.getNextAttemptAt(), row.getInterventionReason());
            }
            case ASIGNACION -> {
                if (row.getVisibility() != ActivityVisibility.INTERNO) {
                    throw new IllegalArgumentException("El hito publico de asignacion no genera evento");
                }
                type = REQUEST_ASSIGNED;
                payload = new DomainEvent.Assigned(request.getId(), request.getFolio(), row.getAssigneeId(),
                        row.getAssigneeName(), row.getPreviousAssigneeId(), row.getReason());
            }
            case CALIFICACION -> {
                type = REQUEST_RATED;
                payload = new DomainEvent.Rated(request.getId(), request.getFolio(),
                        Objects.requireNonNull(request.getSatisfactionScore(), "satisfactionScore"),
                        request.getSatisfactionComment());
            }
            default -> throw new IllegalArgumentException("Marca sin evento: " + row.getKind());
        }
        // El sobre exige actor completo (EP3-01, sección 4). Sin él la operación falla y no queda nada.
        DomainEvent.Actor actor = new DomainEvent.Actor(
                Objects.requireNonNull(row.getActorId(), "actorId"),
                Objects.requireNonNull(row.getActorName(), "actorName"),
                Objects.requireNonNull(row.getActorRole(), "actorRole"));
        DomainEvent event = new DomainEvent(UUID.randomUUID(), type, SCHEMA_VERSION, row.getOccurredAt(),
                PRODUCER, traceId, actor, payload);
        return outbox.save(new OutboxEvent(event.eventId().toString(), request.getId(), type, toJson(event),
                clock.instant()));
    }

    /** El snapshot del alta, o la etiqueta conocida del código: en request.created no puede ir null (EP3-01). */
    private static String procedureLabel(MunicipalRequest request) {
        return request.getProcedureLabel() != null
                ? request.getProcedureLabel()
                : ProcedureTypes.labelOf(request.getProcedureType());
    }

    static String toJson(DomainEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el evento " + event.eventType(), ex);
        }
    }
}
