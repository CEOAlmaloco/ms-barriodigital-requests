package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    /** Mismo largo que la columna procedure_label y que label en catalog. */
    static final int PROCEDURE_LABEL_MAX = 120;

    private final MunicipalRequestRepository repository;
    private final EventPublisher publisher;

    public RequestService(MunicipalRequestRepository repository, EventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Transactional
    public MunicipalRequest create(CreateRequestDto body, CallerContext caller) {
        requireUserId(caller);

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
                procedureLabel
        );
        MunicipalRequest saved = repository.save(request);
        publishAfterCommit(saved, List.of("certificate.receipt"));
        return saved;
    }

    @Transactional(readOnly = true)
    public MunicipalRequest getById(String id, CallerContext caller) {
        requireUserId(caller);
        MunicipalRequest found = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        if (caller.mustFilterOwnRequests() && !caller.userId().equals(found.getSolicitanteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado");
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<MunicipalRequest> list(
            RequestStatus status,
            LocalDate from,
            LocalDate to,
            CallerContext caller
    ) {
        requireUserId(caller);

        Instant fromInstant = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to == null
                ? null
                : to.atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC);

        String ownerFilter = caller.mustFilterOwnRequests() ? caller.userId().trim() : null;
        return repository.search(status, fromInstant, toInstant, ownerFilter);
    }

    /**
     * EP1.5-04: parsea status en el service (como procedureType en create).
     * Expuesto por PUT /api/requests/{id}/status (EP1.5-05).
     */
    @Transactional
    public MunicipalRequest changeStatus(String id, String rawStatus, String rejectionReason, CallerContext caller) {
        requireUserId(caller);
        if (!caller.isFuncionario()) {
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
        publishStatusEventsAfterCommit(previous, saved);
        return saved;
    }

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

    private void publishAfterCommit(MunicipalRequest saved, List<String> types) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Los eventos de estado requieren una transaccion activa");
        }
        RequestEventPayload payload = new RequestEventPayload(saved.getId(), saved.getProcedureType(),
                saved.getProcedureLabel(), saved.getAddress(), saved.getSolicitanteEmail());
        UUID traceId = UUID.randomUUID();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
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
     * Nombre que queda en la fila. El header manda; si viene vacío, el id; si tampoco hay id, "Sin nombre".
     */
    static String snapshotName(String headerName, String solicitanteId) {
        String fromHeader = blankToNull(headerName);
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromId = blankToNull(solicitanteId);
        return fromId != null ? fromId : "Sin nombre";
    }

    /** Email que queda en la fila. Sin header, o en blanco, queda null. No se inventa un destinatario. */
    static String snapshotEmail(String headerEmail) {
        return blankToNull(headerEmail);
    }

    /**
     * Label del tipo que queda en la fila. El header llega codificado en URL (UTF-8).
     * Sin header, en blanco o mal codificado queda null, sin error. Se recorta y se trunca a 120.
     */
    static String snapshotProcedureLabel(String headerLabel) {
        String raw = blankToNull(headerLabel);
        if (raw == null) {
            return null;
        }
        String decoded;
        try {
            decoded = blankToNull(URLDecoder.decode(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (decoded == null || decoded.length() <= PROCEDURE_LABEL_MAX) {
            return decoded;
        }
        return decoded.substring(0, PROCEDURE_LABEL_MAX).trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
