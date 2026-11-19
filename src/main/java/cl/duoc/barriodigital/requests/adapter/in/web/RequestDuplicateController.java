package cl.duoc.barriodigital.requests.adapter.in.web;

import cl.duoc.barriodigital.requests.application.usecase.DuplicateCheckService;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.DuplicateCandidateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Posibles duplicados (EP3-75). Solo lectura: no escribe, no genera eventos y no bloquea el alta.
 * /duplicates es una ruta literal, así que /api/requests/{id} no la captura.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestDuplicateController {

    private final DuplicateCheckService duplicates;

    public RequestDuplicateController(DuplicateCheckService duplicates) {
        this.duplicates = duplicates;
    }

    /** Antes de crear un trámite, solo el Vecino. Las coordenadas llegan como texto para responder 400 propio. */
    @GetMapping("/duplicates")
    public List<DuplicateCandidateView> forNewRequest(
            @RequestParam(required = false) String procedureType,
            @RequestParam(required = false) String latitude,
            @RequestParam(required = false) String longitude,
            @RequestParam(required = false) String sector,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        CallerContext caller = new CallerContext(userId, roles);
        return duplicates.forNewRequest(procedureType, latitude, longitude, sector, caller).stream()
                .map(candidate -> DuplicateCandidateView.of(candidate, caller))
                .toList();
    }

    /** De un trámite ya ingresado, para el Funcionario y el Admin. */
    @GetMapping("/{id}/duplicates")
    public List<DuplicateCandidateView> forRequest(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        CallerContext caller = new CallerContext(userId, roles);
        return duplicates.forRequest(id, caller).stream()
                .map(candidate -> DuplicateCandidateView.of(candidate, caller))
                .toList();
    }
}
