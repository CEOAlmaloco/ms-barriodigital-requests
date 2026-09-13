package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistencia en memoria para smoke test del API.
 * EP1-21 ya conecta el pool Oracle; EP1-14 mueve este CRUD a la base.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final Map<String, MunicipalRequest> store = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<MunicipalRequest> create(@Valid @RequestBody CreateRequestDto body) {
        MunicipalRequest created = new MunicipalRequest(
                body.title(),
                body.description(),
                body.procedureType()
        );
        store.put(created.getId(), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public MunicipalRequest getById(@PathVariable String id) {
        MunicipalRequest found = store.get(id);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado");
        }
        return found;
    }

    @GetMapping
    public List<MunicipalRequest> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        List<MunicipalRequest> result = new ArrayList<>();
        for (MunicipalRequest request : store.values()) {
            if (status != null && request.getStatus() != status) {
                continue;
            }
            if (from != null && request.getCreatedAt().isBefore(from)) {
                continue;
            }
            if (to != null && request.getCreatedAt().isAfter(to)) {
                continue;
            }
            result.add(request);
        }
        return result;
    }
}
