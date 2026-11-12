package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestAssignmentService;
import cl.duoc.barriodigital.requests.service.RequestAttachmentContent;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestListQuery;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import cl.duoc.barriodigital.requests.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.web.dto.AssignRequestDto;
import cl.duoc.barriodigital.requests.web.dto.AssigneeView;
import cl.duoc.barriodigital.requests.web.dto.ChangeStatusDto;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import cl.duoc.barriodigital.requests.web.dto.NotificationPreferenceDto;
import cl.duoc.barriodigital.requests.web.dto.PageView;
import cl.duoc.barriodigital.requests.web.dto.RateRequestDto;
import cl.duoc.barriodigital.requests.web.dto.RegisterGestionDto;
import cl.duoc.barriodigital.requests.web.dto.RequestView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API HTTP de los trámites.
 * La identidad llega en headers del BFF, no en el body. Los trámites salen como {@link RequestView},
 * armado según el rol de quien llama; la entidad nunca se serializa.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;
    private final RequestCreationOrchestrationService creation;
    private final RequestActivityService activityService;
    private final ImageAttachmentSanitizer imageSanitizer;
    private final StaffMemberService staffMembers;
    private final RequestAssignmentService assignment;
    private final NotificationPreferenceService notificationPreferences;

    /** Recibe los servicios del trámite, del alta, del historial, del personal, de la asignación y de preferencias. */
    public RequestController(
            RequestService requestService,
            RequestCreationOrchestrationService creation,
            RequestActivityService activityService,
            ImageAttachmentSanitizer imageSanitizer,
            StaffMemberService staffMembers,
            RequestAssignmentService assignment,
            NotificationPreferenceService notificationPreferences
    ) {
        this.requestService = requestService;
        this.creation = creation;
        this.activityService = activityService;
        this.imageSanitizer = imageSanitizer;
        this.staffMembers = staffMembers;
        this.assignment = assignment;
        this.notificationPreferences = notificationPreferences;
    }

    /** Crea un trámite con el body y la identidad de los headers. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequestView> create(
            @Valid @RequestBody CreateRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName,
            @RequestHeader(value = CallerContext.HEADER_USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_LABEL, required = false) String procedureLabel,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_SLA_DAYS, required = false) String procedureSlaDays
    ) {
        CallerContext caller = new CallerContext(userId, roles, userName, userEmail, procedureLabel, procedureSlaDays);
        MunicipalRequest created = creation.create(body, caller, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(RequestView.of(created, caller));
    }

    /** Crea un trámite con una sola imagen opcional; el archivo se valida y normaliza en el servidor. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RequestView> createWithAttachment(
            @Valid @RequestPart("request") CreateRequestDto body,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName,
            @RequestHeader(value = CallerContext.HEADER_USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_LABEL, required = false) String procedureLabel,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_SLA_DAYS, required = false) String procedureSlaDays
    ) {
        var image = attachment == null ? null : imageSanitizer.sanitize(attachment);
        CallerContext caller = new CallerContext(userId, roles, userName, userEmail, procedureLabel, procedureSlaDays);
        MunicipalRequest created = creation.create(body, caller, image);
        return ResponseEntity.status(HttpStatus.CREATED).body(RequestView.of(created, caller));
    }

    /** Descarga privada: identidad y acceso al trámite se validan antes de devolver los bytes. */
    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> getAttachment(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        RequestAttachmentContent attachment = requestService.getAttachment(id, new CallerContext(userId, roles));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .contentLength(attachment.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(attachment.content());
    }

    /** Cambia el estado. El rol llega en el header. */
    @PutMapping("/{id}/status")
    public RequestView updateStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeStatusDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        CallerContext caller = new CallerContext(userId, roles, userName);
        return RequestView.of(
                requestService.changeStatus(id, body.status(), body.rejectionReason(), body.interventionReason(), caller),
                caller
        );
    }

    /** Encuesta de satisfacción. Solo el Vecino titular y solo en RESUELTO, una vez (EP3-74). */
    @PostMapping("/{id}/rating")
    public RequestView rate(
            @PathVariable String id,
            @Valid @RequestBody RateRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        CallerContext caller = new CallerContext(userId, roles, userName);
        return RequestView.of(requestService.rate(id, body.score(), body.comment(), caller), caller);
    }

    /** Historial, gestiones visibles según el rol y los tiempos que sí se pueden calcular. */
    @GetMapping("/{id}/actividad")
    public ActividadView actividad(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return activityService.get(id, new CallerContext(userId, roles));
    }

    /** Registra una gestión declarada por el funcionario. No confirma que el vecino haya atendido. */
    @PostMapping("/{id}/gestiones")
    public ResponseEntity<ActividadView> registrarGestion(
            @PathVariable String id,
            @Valid @RequestBody RegisterGestionDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        ActividadView view = activityService.registerGestion(id, body, new CallerContext(userId, roles, userName));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** El Funcionario toma un trámite sin responsable (contrato EP3-05, sección 3.1). */
    @PostMapping("/{id}/take")
    public RequestView take(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        CallerContext caller = new CallerContext(userId, roles, userName);
        return RequestView.of(assignment.take(id, caller), caller);
    }

    /**
     * Primera asignación del Admin, o reasignación con motivo del Admin o del responsable (contrato EP3-05, sección 3.2).
     */
    @PutMapping("/{id}/assignee")
    public RequestView assign(
            @PathVariable String id,
            @Valid @RequestBody AssignRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        CallerContext caller = new CallerContext(userId, roles, userName);
        return RequestView.of(assignment.assign(id, body, caller), caller);
    }

    /**
     * Funcionarios a los que se puede asignar un trámite, por nombre. Solo Funcionario y Admin.
     * Es una ruta literal, así que {id} no la captura.
     */
    @GetMapping("/assignees")
    public List<AssigneeView> assignees(
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return staffMembers.assignees(new CallerContext(userId, roles));
    }

    /** Preferencia de avisos por correo del caller (EP3-65). Sin fila → emailEnabled true. */
    @GetMapping("/me/notification-preferences")
    public NotificationPreferenceDto getNotificationPreferences(
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return NotificationPreferenceDto.of(notificationPreferences.get(new CallerContext(userId, roles)));
    }

    /** Guarda si el caller recibe avisos por correo (EP3-65). */
    @PutMapping("/me/notification-preferences")
    public NotificationPreferenceDto putNotificationPreferences(
            @Valid @RequestBody NotificationPreferenceDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return NotificationPreferenceDto.of(
                notificationPreferences.update(body.emailEnabled(), new CallerContext(userId, roles)));
    }

    /** Devuelve un trámite. Quien no es personal solo obtiene el suyo. */
    @GetMapping("/{id}")
    public RequestView getById(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        CallerContext caller = new CallerContext(userId, roles);
        return RequestView.of(requestService.getById(id, caller), caller);
    }

    /**
     * Lista trámites por página (EP3-30). Las fechas from y to llegan como día, por ejemplo 2026-10-25.
     * assignee=me|none filtra por responsable, solo para staff. Las reglas de cada parámetro están en el README.
     */
    @GetMapping
    public PageView<RequestView> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String open,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String procedureType,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        CallerContext caller = new CallerContext(userId, roles);
        RequestListQuery params = new RequestListQuery(
                status, from, to, assignee, open, q, procedureType, sector, page, size, sort);
        return PageView.of(requestService.list(params, caller), request -> RequestView.of(request, caller));
    }
    
    /**
     * Lista fija de tipos de trámite. La fuente vigente es el servicio catalog.
     * Sigue respondiendo para no romper a quien todavía la pide, y va antes de /{id}.
     *
     * @deprecated la fuente de verdad de los tipos de trámite es GET /api/catalog/procedures.
     */
    @Deprecated
    @GetMapping("/meta/procedure-types")
    public Map<String, String> procedureTypes() {
        return ProcedureTypes.asMap();
    }

    /** Devuelve los cambios de estado permitidos para que el cliente no copie la máquina de estados. */
    @GetMapping("/meta/transitions")
    public Map<String, List<String>> transitions() {
        return RequestTransitions.asNamesMap();
    }
}
