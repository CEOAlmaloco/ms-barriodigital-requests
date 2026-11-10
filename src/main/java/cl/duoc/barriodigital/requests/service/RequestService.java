package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.CivilTime;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestAttachment;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestAttachmentRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
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

/**
 * Crea trámites, los consulta y cambia su estado.
 * Publica el evento después del commit. Si RabbitMQ está caído se pierde el aviso, no el trámite, y no hay outbox.
 */
@Service
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    /** Tope de la columna procedure_label y del label en catalog. */
    static final int PROCEDURE_LABEL_MAX = 120;

    /** Tope de las columnas solicitante_nombre y actor_name. */
    static final int NAME_MAX = 200;

    /** Tope de intervention_reason, igual que rejectionReason. */
    static final int INTERVENTION_REASON_MAX = 500;

    private final MunicipalRequestRepository repository;
    private final RequestActivityRepository activity;
    private final RequestAttachmentRepository attachments;
    private final EventPublisher publisher;
    private final FolioCounterService folios;

    /** Recibe los repositorios del trámite y su imagen, el historial, el publicador de eventos y el contador de folios. */
    public RequestService(
            MunicipalRequestRepository repository,
            RequestActivityRepository activity,
            RequestAttachmentRepository attachments,
            EventPublisher publisher,
            FolioCounterService folios
    ) {
        this.repository = repository;
        this.activity = activity;
        this.attachments = attachments;
        this.publisher = publisher;
        this.folios = folios;
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
        request.setHasAttachment(image != null);
        // El número se toma al final, ya validado el alta, para que el bloqueo del contador dure lo menos posible.
        request.assignFolio(folios.next(Folio.yearOf(createdAt)));
        MunicipalRequest saved = repository.save(request);
        if (image != null) {
            attachments.save(new RequestAttachment(saved, image));
        }
        activity.save(RequestActivity.created(
                saved.getId(),
                saved.getCreatedAt(),
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId())
        ));
        publishAfterCommit(saved, List.of("certificate.receipt"));
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
     * Lista trámites. Quien no es personal solo ve los suyos.
     * from y to son días civiles en America/Santiago y los dos se incluyen completos.
     */
    @Transactional(readOnly = true)
    public List<MunicipalRequest> list(
            RequestStatus status,
            LocalDate from,
            LocalDate to,
            CallerContext caller
    ) {
        requireUserId(caller);

        Instant fromInstant = from == null ? null : startOfCivilDay(from);
        // Inicio del día siguiente, sin incluirlo: cubre el día entero, con microsegundos y cambios de hora.
        Instant beforeInstant = to == null ? null : startOfCivilDay(to.plusDays(1));

        String ownerFilter = caller.mustFilterOwnRequests() ? caller.userId().trim() : null;
        return repository.search(status, fromInstant, beforeInstant, ownerFilter);
    }

    /** Primer instante del día en Chile. Si ese día la medianoche no existe por el cambio de hora, es la 01:00. */
    static Instant startOfCivilDay(LocalDate day) {
        return day.atStartOfDay(CivilTime.ZONE).toInstant();
    }

    /**
     * Cambia el estado. Lo hace el Funcionario, o el Admin como intervención con interventionReason (EP3-04, sección 3).
     * Si el estado es RECHAZADO, el motivo es obligatorio y no supera 500 caracteres. Bloquea la fila para que dos cambios no se pisen.
     * Orden de errores de EP3-04, sección 5: 403 por rol, 400 por body, 404 y 409.
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

        if (!RequestTransitions.isAllowed(found.getStatus(), target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede pasar de " + found.getStatus() + " a " + target
            );
        }

        RequestStatus previous = found.getStatus();
        found.applyStatus(target, rejectionReason);
        MunicipalRequest saved = repository.save(found);
        activity.save(RequestActivity.statusChanged(
                saved.getId(),
                saved.getUpdatedAt(),
                caller.userId().trim(),
                snapshotName(caller.userName(), caller.userId()),
                previous,
                saved.getStatus(),
                target == RequestStatus.RECHAZADO ? rejectionReason : null,
                intervention
        ));
        publishStatusEventsAfterCommit(previous, saved);
        return saved;
    }

    /**
     * Elige los eventos de un cambio de estado y los publica después del commit.
     * INGRESADO a ADMITIDO publica email.admitted, EN_GESTION a EN_TERRENO publica crew.ticket y email.visit, y EN_TERRENO a RESUELTO publica email.resolved y certificate.resolved.
     * Pasar a RECHAZADO o a EN_GESTION no publica.
     */
    private void publishStatusEventsAfterCommit(RequestStatus previous, MunicipalRequest saved) {
        List<String> types;
        if (previous == RequestStatus.INGRESADO && saved.getStatus() == RequestStatus.ADMITIDO) {
            types = List.of("email.admitted");
        } else if (previous == RequestStatus.EN_GESTION && saved.getStatus() == RequestStatus.EN_TERRENO) {
            types = List.of("crew.ticket", "email.visit");
        } else if (previous == RequestStatus.EN_TERRENO && saved.getStatus() == RequestStatus.RESUELTO) {
            types = List.of("email.resolved", "certificate.resolved");
        } else {
            return;
        }

        publishAfterCommit(saved, types);
    }

    /**
     * Publica después del commit, cuando el trámite ya quedó guardado.
     * Si un evento falla, se registra y se intenta el siguiente. Si RabbitMQ está caído se pierde el aviso, no el trámite, y no hay outbox.
     */
    private void publishAfterCommit(MunicipalRequest saved, List<String> types) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Los eventos de estado requieren una transaccion activa");
        }
        RequestEventPayload payload = new RequestEventPayload(saved.getId(), saved.getFolio(), saved.getProcedureType(),
                saved.getProcedureLabel(), saved.getAddress(), saved.getSolicitanteEmail());
        UUID traceId = UUID.randomUUID();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** Cuando el commit terminó, publica cada evento. Si uno falla, lo registra y sigue con el siguiente. */
            @Override
            public void afterCommit() {
                for (String type : types) {
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
