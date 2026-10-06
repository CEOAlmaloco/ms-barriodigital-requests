package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class RequestService {

    private final MunicipalRequestRepository repository;

    public RequestService(MunicipalRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MunicipalRequest create(CreateRequestDto body, CallerContext caller) {
        requireUserId(caller);

        String procedureType = ProcedureTypes.normalize(body.procedureType());
        if (!ProcedureTypes.isAllowed(procedureType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "procedureType invalido. Codigos: " + String.join(", ", ProcedureTypes.CODES)
            );
        }

        String address = body.address().trim();
        String title = ProcedureTypes.buildTitle(procedureType, address);

        MunicipalRequest request = new MunicipalRequest(
                title,
                body.description().trim(),
                procedureType,
                address,
                caller.userId().trim()
        );
        return repository.save(request);
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
     * El PUT del controller llega en EP1.5-05.
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

        MunicipalRequest found = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        if (!RequestTransitions.isAllowed(found.getStatus(), target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede pasar de " + found.getStatus() + " a " + target
            );
        }

        found.applyStatus(target, rejectionReason);
        return repository.save(found);
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

    private static void requireUserId(CallerContext caller) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."
            );
        }
    }
}
