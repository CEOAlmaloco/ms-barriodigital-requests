package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.StaffMember;
import cl.duoc.barriodigital.requests.domain.StoredInstant;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.persistence.StaffMemberRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssignRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Toma, asignación y reasignación del responsable (contrato EP3-05, secciones 2 a 6).
 * Lee el trámite con findByIdForUpdate y evalúa permisos, motivo y estado dentro del bloqueo,
 * en la misma transacción que el cambio y la marca del historial.
 */
@Service
public class RequestAssignmentService {

    /** Tope de reason, como rejectionReason. */
    static final int REASON_MAX = 500;

    private static final Set<RequestStatus> CLOSED = EnumSet.of(
            RequestStatus.RESUELTO, RequestStatus.RECHAZADO, RequestStatus.CANCELADO);

    private final MunicipalRequestRepository requests;
    private final RequestActivityRepository activity;
    private final StaffMemberRepository staff;
    private final DomainEventRecorder events;

    public RequestAssignmentService(
            MunicipalRequestRepository requests,
            RequestActivityRepository activity,
            StaffMemberRepository staff,
            DomainEventRecorder events
    ) {
        this.requests = requests;
        this.activity = activity;
        this.staff = staff;
        this.events = events;
    }

    /**
     * El Funcionario se asigna un trámite sin responsable. El Admin no toma: asigna.
     * Orden de errores de EP3-05, sección 4: 403 por rol, 404, y 409 si está cerrado o ya tiene responsable.
     */
    @Transactional
    public MunicipalRequest take(String requestId, CallerContext caller) {
        requireUserId(caller);
        if (caller.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un Funcionario puede tomar un tramite");
        }
        if (!caller.isFuncionario()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, StaffMemberService.ROLE_NOT_ALLOWED);
        }
        MunicipalRequest request = locked(requestId);
        if (CLOSED.contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede asignar un tramite cerrado");
        }
        if (request.hasAssignee()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite ya tiene responsable");
        }
        String oid = caller.userId().trim();
        String name = RequestService.snapshotName(caller.userName(), oid);
        return record(request, caller, oid, name, null);
    }

    /**
     * Primera asignación (solo el Admin) o reasignación (el Admin, o el Funcionario responsable, con motivo).
     * Orden de errores de EP3-05, sección 4: 403 por rol, 400 por body, 404, 403 por responsable,
     * 400 por motivo de reasignación y 409. El motivo y el responsable solo se conocen con la fila bloqueada.
     */
    @Transactional
    public MunicipalRequest assign(String requestId, AssignRequestDto body, CallerContext caller) {
        requireUserId(caller);
        if (!caller.isAdmin() && !caller.isFuncionario()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, StaffMemberService.ROLE_NOT_ALLOWED);
        }
        String assigneeId = body.assigneeId() == null ? "" : body.assigneeId().trim();
        if (assigneeId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assigneeId es obligatorio");
        }
        String reason = body.reason() == null ? null : body.reason().trim();
        if (reason != null && reason.length() > REASON_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason no puede superar 500 caracteres");
        }
        StaffMember assignee = staff.findById(assigneeId)
                .filter(member -> CallerContext.FUNCIONARIO.equals(member.getRole()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "El destinatario debe ser un Funcionario de la lista"));

        MunicipalRequest request = locked(requestId);
        boolean reassignment = request.hasAssignee();
        if (caller.isFuncionario() && !reassignment) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un tramite sin responsable se toma, no se asigna");
        }
        if (caller.isFuncionario() && !caller.userId().trim().equals(request.getAssigneeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el responsable del tramite puede reasignarlo");
        }
        if (reassignment && (reason == null || reason.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason es obligatorio al reasignar");
        }
        if (CLOSED.contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede asignar un tramite cerrado");
        }
        if (assigneeId.equals(request.getAssigneeId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite ya esta asignado a ese funcionario");
        }
        // En la primera asignación el motivo se ignora: no se guarda (EP3-05, sección 3.3).
        return record(request, caller, assignee.getOid(), assignee.getName(), reassignment ? reason : null);
    }

    /**
     * Cambia el responsable y deja la marca interna. Solo la primera asignación agrega el hito público,
     * en el mismo instante. Cada operación registra un solo request.assigned, desde la marca interna.
     */
    private MunicipalRequest record(
            MunicipalRequest request,
            CallerContext caller,
            String assigneeId,
            String assigneeName,
            String reason
    ) {
        String previousId = request.getAssigneeId();
        String previousName = request.getAssigneeName();
        request.assignTo(assigneeId, assigneeName);
        MunicipalRequest saved = requests.save(request);
        Instant now = StoredInstant.now();
        String actorId = caller.userId().trim();
        RequestActivity assigned = activity.save(RequestActivity.assigned(
                saved.getId(),
                now,
                actorId,
                RequestService.snapshotName(caller.userName(), actorId),
                caller.role(),
                assigneeId,
                assigneeName,
                previousId,
                previousName,
                reason
        ));
        if (previousId == null) {
            activity.save(RequestActivity.assignmentMilestone(saved.getId(), now));
        }
        // Sin comandos de RabbitMQ: el traceId identifica solo esta asignación.
        events.record(saved, assigned, UUID.randomUUID());
        return saved;
    }

    private MunicipalRequest locked(String requestId) {
        return requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
    }

    private static void requireUserId(CallerContext caller) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."
            );
        }
    }
}
