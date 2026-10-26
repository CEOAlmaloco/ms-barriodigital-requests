package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.web.dto.ChangeStatusDto;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API HTTP de los trámites.
 * La identidad llega en headers del BFF, no en el body.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    /** Recibe el servicio que aplica las reglas del trámite. */
    public RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    /** Crea un trámite con el body y la identidad de los headers. */
    @PostMapping
    public ResponseEntity<MunicipalRequest> create(
            @Valid @RequestBody CreateRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName,
            @RequestHeader(value = CallerContext.HEADER_USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_LABEL, required = false) String procedureLabel
    ) {
        MunicipalRequest created = requestService.create(
                body,
                new CallerContext(userId, roles, userName, userEmail, procedureLabel)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Cambia el estado. El rol llega en el header. */
    @PutMapping("/{id}/status")
    public MunicipalRequest updateStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeStatusDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.changeStatus(
                id,
                body.status(),
                body.rejectionReason(),
                new CallerContext(userId, roles)
        );
    }

    /** Devuelve un trámite. Quien no es personal solo obtiene el suyo. */
    @GetMapping("/{id}")
    public MunicipalRequest getById(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.getById(id, new CallerContext(userId, roles));
    }

    /** Lista trámites. Las fechas from y to llegan como día, por ejemplo 2026-10-25. */
    @GetMapping
    public List<MunicipalRequest> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.list(status, from, to, new CallerContext(userId, roles));
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
