package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CRUD Oracle. Identidad y roles llegan del BFF (headers), no del body.
 * from/to aceptan fecha simple {@code yyyy-MM-dd} (mat-datepicker).
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    public RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    public ResponseEntity<MunicipalRequest> create(
            @Valid @RequestBody CreateRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        MunicipalRequest created = requestService.create(body, new CallerContext(userId, roles));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public MunicipalRequest getById(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.getById(id, new CallerContext(userId, roles));
    }

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

    /** Catálogo de códigos para alinear el combo del front. */
    @GetMapping("/meta/procedure-types")
    public Map<String, String> procedureTypes() {
        return ProcedureTypes.asMap();
    }
}
