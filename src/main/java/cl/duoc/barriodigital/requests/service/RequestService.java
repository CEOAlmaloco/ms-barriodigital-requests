package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.CivilTime;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestAttachment;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.SortField;
import cl.duoc.barriodigital.requests.persistence.RequestAttachmentRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.assignedTo;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.createdBefore;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.createdFrom;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.matches;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.open;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.orderedBy;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.ownedBy;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.procedureTypeIs;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.sectorIs;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.statusIs;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.unassigned;

/**
 * Crea trámites, los consulta y cambia su estado.
 * Cada cambio deja su evento de Kafka en el outbox, en la misma transacción (EP3-02). Los comandos de RabbitMQ se
 * publican después del commit, con el mismo traceId: si RabbitMQ está caído se pierde el aviso, no el trámite.
 */
@Service
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    /** Tope de la columna procedure_label y del label en catalog. */
    static final int PROCEDURE_LABEL_MAX = 120;

    /** Tope de las columnas solicitante_nombre y actor_name. */
    static final int NAME_MAX = 200;

    /** Valores del filtro assignee del listado. */
    static final String ASSIGNEE_ME = "me";
    static final String ASSIGNEE_NONE = "none";

    /** Página del listado (EP3-07, sección 2.1) y largo máximo de q. */
    static final int LIST_DEFAULT_SIZE = 20;
    static final int LIST_MAX_SIZE = 50;
    static final int LIST_SEARCH_MAX = 100;

    /** Tope de intervention_reason, igual que rejectionReason. */
    static final int INTERVENTION_REASON_MAX = 500;

    /** Rango de slaBusinessDays en catalog (EP3-06, sección 1.1). */
    static final int SLA_DAYS_MIN = 1;
    static final int SLA_DAYS_MAX = 120;

    private final MunicipalRequestRepository repository;
    private final RequestActivityRepository activity;
    private final RequestAttachmentRepository attachments;
    private final EventPublisher publisher;
    private final FolioCounterService folios;
    private final DomainEventRecorder events;
    private final NotificationPreferenceService notificationPreferences;

    /**
     * Recibe los repositorios del trámite y su imagen, el historial, el publicador de RabbitMQ, el contador de folios,
     * el registro del outbox y las preferencias de aviso (EP3-65).
     */
    public RequestService(
            MunicipalRequestRepository repository,
            RequestActivityRepository activity,
            RequestAttachmentRepository attachments,
            EventPublisher publisher,
            FolioCounterService folios,
            DomainEventRecorder events,
            NotificationPreferenceService notificationPreferences
    ) {
        this.repository = repository;
        this.activity = activity;
        this.attachments = attachments;
        this.publisher = publisher;
        this.folios = folios;
        this.events = events;
        this.notificationPreferences = notificationPreferences;
    }

    /**
     * Crea el trámite en INGRESADO, con su imagen opcional y su folio, en una sola transacción.
     * Se llama solo desde {@link RequestCreationOrchestrationService}, que ya dejó creada la fila del año
     * y pasa el instante leído del Clock. Después del commit publica el evento certificate.receipt.
     */
    @Transactional
    public MunicipalRequest create(
            CreateRequestDto body,
            CallerContext caller,
            ImageAttachmentSanitizer.SanitizedImage image,
            Instant createdAt
    ) {
        requireUserId(caller);
        // Solo el Vecino crea trámites (EP3-04, sección 2). El BFF aplica la misma regla.
        if (!caller.isVecino()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un Vecino puede crear un tramite");
        }

        String procedureType = ProcedureTypes.normalize(body.procedureType());
        if (!ProcedureTypes.hasValidFormat(procedureType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "procedureType invalido. Debe ser minusculas, numeros, guion o guion bajo (maximo 40 caracteres)"
            );
        }

        String address = body.address().trim();
        String procedureLabel = snapshotProcedureLabel(caller.procedureLabel());
        String title = ProcedureTypes.buildTitle(procedureType, address, procedureLabel);

        MunicipalRequest request = new MunicipalRequest(
                title,
                body.description().trim(),
                procedureType,
                address,
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId()),
                snapshotEmail(caller.userEmail()),
                procedureLabel,
                createdAt
        );
        LocationRules.apply(request, body);
        request.snapshotSlaBusinessDays(snapshotSlaDays(caller.procedureSlaDays()));
        request.setHasAttachment(image != null);
        // El número se toma al final, ya validado el alta, para que el bloqueo del contador dure lo menos posible.
        request.assignFolio(folios.next(Folio.yearOf(createdAt)));
        MunicipalRequest saved = repository.save(request);
        if (image != null) {
            attachments.save(new RequestAttachment(saved, image));
        }
        UUID traceId = UUID.randomUUID();
        RequestActivity created = activity.save(RequestActivity.created(
                saved.getId(),
                saved.getCreatedAt(),
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId()),
                caller.role()
        ));
        events.record(saved, created, traceId);
        publishAfterCommit(saved, List.of("certificate.receipt"), traceId);
        return saved;
    }

    /** Devuelve la imagen solo al titular del trámite o al personal autorizado. */
    @Transactional(readOnly = true)
    public RequestAttachmentContent getAttachment(String id, CallerContext caller) {
        MunicipalRequest request = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        requireAttachmentAccess(request, caller);
        RequestAttachment attachment = attachments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada"));
        return new RequestAttachmentContent(
                attachment.getContent(),
                attachment.getContentType(),
                attachment.getFileName()
        );
    }

    /** Devuelve un trámite. Si es de otra persona y quien pide no es personal, responde que no existe. */
    @Transactional(readOnly = true)
    public MunicipalRequest getById(String id, CallerContext caller) {
        requireUserId(caller);
        MunicipalRequest found = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        requireAttachmentAccess(found, caller);
        return found;
    }

    private void requireAttachmentAccess(MunicipalRequest request, CallerContext caller) {
        requireUserId(caller);
        if (caller.mustFilterOwnRequests() && !caller.userId().trim().equals(request.getSolicitanteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado");
        }
    }

    /**
     * Lista trámites por página (EP3-30). Quien no es personal solo ve los suyos.
     * from y to son días civiles en America/Santiago y los dos se incluyen completos.
     * assignee (solo staff, EP3-05 sección 8): me son los trámites de quien llama y none los que no tienen responsable.
     * Para el Admin y el Auditor, me es vacío, porque nunca son responsables.
     * Primero el 403 de assignee y después los 400, con los mensajes de EP3-07, sección 6.
     */
    @Transactional(readOnly = true)
    public Page<MunicipalRequest> list(RequestListQuery params, CallerContext caller) {
        requireUserId(caller);
        String assigneeId = null;
        boolean unassigned = false;
        String assignee = blankToNull(params.assignee());
        if (assignee != null) {
            if (!caller.isStaff()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El filtro assignee es solo para staff");
            }
            switch (assignee) {
                case ASSIGNEE_ME -> assigneeId = caller.userId().trim();
                case ASSIGNEE_NONE -> unassigned = true;
                default -> throw badRequest("assignee debe ser me o none");
            }
        }
        if (params.from() != null && params.to() != null && params.from().isAfter(params.to())) {
            throw badRequest("from no puede ser posterior a to");
        }
        Boolean open = parseOpen(params.open());
        String text = parseSearchText(params.q());
        String sector = parseSector(params.sector());
        PageRequest pageable = pageRequest(params.page(), params.size());
        Specification<MunicipalRequest> order = parseSort(params.sort());

        Instant fromInstant = params.from() == null ? null : startOfCivilDay(params.from());
        // Inicio del día siguiente, sin incluirlo: cubre el día entero, con microsegundos y cambios de hora.
        Instant beforeInstant = params.to() == null ? null : startOfCivilDay(params.to().plusDays(1));
        String ownerFilter = caller.mustFilterOwnRequests() ? caller.userId().trim() : null;

        Specification<MunicipalRequest> spec = Specification.where(ownedBy(ownerFilter))
                .and(statusIs(params.status()))
                .and(open(open))
                .and(createdFrom(fromInstant))
                .and(createdBefore(beforeInstant))
                .and(assignedTo(assigneeId))
                .and(unassigned(unassigned))
                .and(procedureTypeIs(ProcedureTypes.normalize(blankToNull(params.procedureType()))))
                .and(sectorIs(sector))
                .and(matches(text))
                .and(order);
        // Spring no acepta un offset mayor que un int. Esa página está fuera de rango: vacía, con el total real.
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            return new PageImpl<>(List.of(), pageable, repository.count(spec));
        }
        return repository.findAll(spec, pageable);
    }

    /** open acepta solo true o false. */
    static Boolean parseOpen(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw badRequest("open debe ser true o false");
        };
    }

    static String parseSearchText(String raw) {
        String text = blankToNull(raw);
        if (text != null && text.length() > LIST_SEARCH_MAX) {
            throw badRequest("q no puede superar " + LIST_SEARCH_MAX + " caracteres");
        }
        return text;
    }

    /** El sector es una de las claves de LocationRules, las mismas que acepta el alta. */
    static String parseSector(String raw) {
        String sector = blankToNull(raw);
        if (sector != null && !LocationRules.SECTORS.containsKey(sector)) {
            throw badRequest("sector no es valido");
        }
        return sector;
    }

    /** Página desde 0 y tamaño de 1 a 50, como en EP3-07, sección 2.1. Sin orden: lo pone parseSort. */
    static PageRequest pageRequest(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? LIST_DEFAULT_SIZE : size;
        if (pageNumber < 0) {
            throw badRequest("page debe ser 0 o mayor");
        }
        if (pageSize < 1 || pageSize > LIST_MAX_SIZE) {
            throw badRequest("size debe estar entre 1 y " + LIST_MAX_SIZE);
        }
        return PageRequest.of(pageNumber, pageSize);
    }

    /** sort es campo o campo,asc|desc, con createdAt, status o folio. Sin dirección es ascendente, como en Spring. */
    static Specification<MunicipalRequest> parseSort(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return orderedBy(SortField.CREATED_AT, false);
        }
        String[] parts = value.split(",", -1);
        SortField field = SortField.fromApiName(parts[0]);
        if (field == null || parts.length > 2) {
            throw badRequest("sort no es valido");
        }
        if (parts.length == 1 || parts[1].equalsIgnoreCase("asc")) {
            return orderedBy(field, true);
        }
        if (parts[1].equalsIgnoreCase("desc")) {
            return orderedBy(field, false);
        }
        throw badRequest("sort no es valido");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** Primer instante del día en Chile. Si ese día la medianoche no existe por el cambio de hora, es la 01:00. */
    static Instant startOfCivilDay(LocalDate day) {
        return day.atStartOfDay(CivilTime.ZONE).toInstant();
    }

    /**
     * Cambia el estado. Lo hace el Funcionario, o el Admin como intervención con interventionReason (EP3-04, sección 3).
     * Si el estado es RECHAZADO, el motivo es obligatorio y no supera 500 caracteres. Bloquea la fila para que dos cambios no se pisen.
     * Orden de errores de EP3-04, sección 5: 403 por rol, 400 por body, 404, 403 si el Funcionario no es el responsable
     * y 409.
     */
    @Transactional
    public MunicipalRequest changeStatus(
            String id,
            String rawStatus,
            String rejectionReason,
            String interventionReason,
            CallerContext caller
    ) {
        requireUserId(caller);
        // El texto del 403 se mantiene: el BFF y el frontend lo comparan hasta que EP3-67 traiga códigos estables.
        if (!caller.isFuncionario() && !caller.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un Funcionario puede cambiar el estado");
        }

        RequestStatus target = parseStatus(rawStatus);
        if (target == RequestStatus.RECHAZADO) {
            String reason = rejectionReason == null ? "" : rejectionReason.trim();
            if (reason.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "rejectionReason es obligatorio cuando el estado es RECHAZADO"
                );
            }
            if (reason.length() > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rejectionReason no puede superar 500 caracteres");
            }
            rejectionReason = reason;
        }
        String intervention = interventionReason(caller, interventionReason);

        // El bloqueo cubre lectura, validación y escritura de cualquier transición del mismo trámite.
        MunicipalRequest found = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        requireResponsible(found, caller);

        if (!RequestTransitions.isAllowed(found.getStatus(), target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede pasar de " + found.getStatus() + " a " + target
            );
        }

        RequestStatus previous = found.getStatus();
        found.applyStatus(target, rejectionReason);
        MunicipalRequest saved = repository.save(found);
        UUID traceId = UUID.randomUUID();
        RequestActivity changed = activity.save(RequestActivity.statusChanged(
                saved.getId(),
                saved.getUpdatedAt(),
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId()),
                caller.role(),
                previous,
                saved.getStatus(),
                target == RequestStatus.RECHAZADO ? rejectionReason : null,
                intervention
        ));
        events.record(saved, changed, traceId);
        publishStatusEventsAfterCommit(previous, saved, traceId);
        return saved;
    }

    /**
     * Encuesta de satisfacción (EP3-74). Solo el Vecino titular, solo en RESUELTO, una sola vez.
     * Orden: 400 sin identidad, 403 por rol, 404, 403 si no es el titular, 409 si no está RESUELTO o ya calificó.
     */
    @Transactional
    public MunicipalRequest rate(String id, Integer score, String comment, CallerContext caller) {
        requireUserId(caller);
        if (!caller.isVecino()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el Vecino titular puede calificar el tramite");
        }
        if (score == null || score < 1 || score > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score debe estar entre 1 y 5");
        }
        String cleaned = blankToNull(comment);
        if (cleaned != null && cleaned.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment no puede superar 500 caracteres");
        }

        MunicipalRequest found = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        if (!caller.userId().trim().equals(found.getSolicitanteId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el titular del tramite puede calificarlo");
        }
        if (found.getStatus() != RequestStatus.RESUELTO) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Solo se puede calificar un tramite RESUELTO"
            );
        }
        if (found.isRated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite ya fue calificado");
        }

        found.applyRating(score, cleaned);
        MunicipalRequest saved = repository.save(found);
        UUID traceId = UUID.randomUUID();
        RequestActivity rated = activity.save(RequestActivity.rated(
                saved.getId(),
                saved.getUpdatedAt(),
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId()),
                caller.role(),
                score,
                cleaned
        ));
        events.record(saved, rated, traceId);
        return saved;
    }

    /**
     * Elige los eventos de un cambio de estado y los publica después del commit.
     * INGRESADO a ADMITIDO publica email.admitted, EN_GESTION a EN_TERRENO publica crew.ticket y email.visit, EN_TERRENO a RESUELTO publica email.resolved y certificate.resolved, y cualquier paso a RECHAZADO publica email.rejected (EP3-33).
     * Pasar a EN_GESTION no publica.
     */
    private void publishStatusEventsAfterCommit(RequestStatus previous, MunicipalRequest saved, UUID traceId) {
        List<String> types;
        if (saved.getStatus() == RequestStatus.RECHAZADO) {
            types = List.of("email.rejected");
        } else if (previous == RequestStatus.INGRESADO && saved.getStatus() == RequestStatus.ADMITIDO) {
            types = List.of("email.admitted");
        } else if (previous == RequestStatus.EN_GESTION && saved.getStatus() == RequestStatus.EN_TERRENO) {
            types = List.of("crew.ticket", "email.visit");
        } else if (previous == RequestStatus.EN_TERRENO && saved.getStatus() == RequestStatus.RESUELTO) {
            types = List.of("email.resolved", "certificate.resolved");
        } else {
            return;
        }

        publishAfterCommit(saved, types, traceId);
    }

    /**
     * Publica en RabbitMQ después del commit, cuando el trámite ya quedó guardado, con el traceId del evento de Kafka.
     * Si el titular desactivó avisos por correo (EP3-65), se omiten los type email.*; certificate.* y crew.ticket salen igual.
     * El payload se arma desde el trámite, no desde la actividad: lleva rejectionReason y nunca interventionReason.
     * Si un comando falla, se registra y se intenta el siguiente. RabbitMQ no usa el outbox: un aviso puede perderse.
     */
    private void publishAfterCommit(MunicipalRequest saved, List<String> types, UUID traceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Los eventos de estado requieren una transaccion activa");
        }
        List<String> toPublish = filterEmailCommands(
                notificationPreferences.isEmailEnabled(saved.getSolicitanteId()), types);
        if (toPublish.isEmpty()) {
            return;
        }
        RequestEventPayload payload = new RequestEventPayload(saved.getId(), saved.getFolio(), saved.getProcedureType(),
                saved.getProcedureLabel(), saved.getAddress(), saved.getSolicitanteEmail(), saved.getRejectionReason());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** Cuando el commit terminó, publica cada evento. Si uno falla, lo registra y sigue con el siguiente. */
            @Override
            public void afterCommit() {
                for (String type : toPublish) {
                    try {
                        publisher.publish(type, payload.requestId(), payload, traceId);
                    } catch (RuntimeException ex) {
                        // Protege el resultado HTTP y permite intentar el siguiente evento del cambio.
                        log.error("Fallo de entrega posterior al commit type={} correlationId={} error={}",
                                type, payload.requestId(), ex.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    /**
     * Si el correo está desactivado, quita los comandos email.*. Deja certificate.* y crew.ticket.
     */
    static List<String> filterEmailCommands(boolean emailEnabled, List<String> types) {
        if (emailEnabled || types == null || types.isEmpty()) {
            return types == null ? List.of() : types;
        }
        return types.stream().filter(type -> !type.startsWith("email.")).toList();
    }

    /** Interpreta el estado escrito. Si no es uno de los seis, responde 400. */
    static RequestStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status es obligatorio");
        }
        try {
            return RequestStatus.valueOf(rawStatus.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status invalido. Valores: INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO"
            );
        }
    }

    /**
     * Nombre que queda guardado en el trámite y en el historial. Llega codificado en URL para conservar tildes.
     * Si el header viene vacío, mal codificado o con caracteres de control, usa el id. Si tampoco hay id, queda "Sin nombre".
     * No supera 200 caracteres.
     */
    public static String snapshotName(String headerName, String solicitanteId) {
        String fromHeader = truncate(decodeHeader(headerName), NAME_MAX);
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromId = blankToNull(solicitanteId);
        return fromId != null ? fromId : "Sin nombre";
    }

    /**
     * Solo el responsable opera (EP3-04, sección 3). Se llama con la fila ya bloqueada, en la misma transacción
     * que la operación: así una reasignación simultánea no deja pasar al responsable anterior. El Admin opera
     * cualquier trámite como intervención.
     */
    static void requireResponsible(MunicipalRequest request, CallerContext caller) {
        if (caller.isFuncionario() && !caller.userId().trim().equals(request.getAssigneeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el responsable del tramite puede operarlo");
        }
    }

    /**
     * Motivo de intervención que queda guardado (EP3-04, sección 3). Es obligatorio para el Admin, hasta 500 caracteres
     * después de recortar. Para el Funcionario se ignora, como rejectionReason fuera de un rechazo.
     */
    static String interventionReason(CallerContext caller, String raw) {
        if (!caller.isAdmin()) {
            return null;
        }
        String reason = blankToNull(raw);
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interventionReason es obligatorio para el Admin");
        }
        if (reason.length() > INTERVENTION_REASON_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interventionReason no puede superar 500 caracteres");
        }
        return reason;
    }

    /** Correo que queda guardado. Si el header viene vacío, queda null. No se inventa uno. */
    static String snapshotEmail(String headerEmail) {
        return blankToNull(headerEmail);
    }

    /**
     * Etiqueta del tipo que queda guardada. Llega codificada en URL para conservar tildes.
     * Si falta o está mal codificada, queda null. No supera 120 caracteres.
     */
    static String snapshotProcedureLabel(String headerLabel) {
        return truncate(decodeHeader(headerLabel), PROCEDURE_LABEL_MAX);
    }

    /**
     * Plazo del header X-Procedure-Sla-Days: entero de 1 a 120, o null si no viene (tipo sin plazo).
     * Un valor inválido sería un error del BFF: queda null con WARN y no bloquea el alta, como X-Procedure-Label.
     */
    static Integer snapshotSlaDays(String header) {
        String raw = blankToNull(header);
        if (raw == null) {
            return null;
        }
        try {
            int days = Integer.parseInt(raw);
            if (days >= SLA_DAYS_MIN && days <= SLA_DAYS_MAX) {
                return days;
            }
        } catch (NumberFormatException ex) {
            // Cae al WARN.
        }
        // Sin caracteres de control, para que el valor no pueda escribir líneas falsas en el log.
        log.warn("X-Procedure-Sla-Days invalido, el tramite queda sin plazo: valor={}",
                truncate(raw, 20).replaceAll("\\p{Cntrl}", "?"));
        return null;
    }

    /**
     * Decodifica un header que el BFF manda codificado en URL (UTF-8).
     * Vacío, mal codificado o con caracteres de control (saltos de línea, tabuladores) cuenta como ausente.
     */
    private static String decodeHeader(String header) {
        String raw = blankToNull(header);
        if (raw == null) {
            return null;
        }
        String decoded;
        try {
            decoded = blankToNull(URLDecoder.decode(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (decoded == null || decoded.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return decoded;
    }

    /** Recorta al largo de la columna sin partir un carácter de dos unidades, como un emoji. */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end).trim();
    }

    /** Un texto vacío se trata como ausente. */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Exige el id de quien llama. Sin el header X-User-Id no continúa. */
    private static void requireUserId(CallerContext caller) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."
            );
        }
    }
}
