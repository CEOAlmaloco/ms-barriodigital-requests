package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.RequestService;
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

import java.time.Instant;
import java.util.List;

/**
 * CRUD de trámites persistido en Oracle Autonomous (EP1-14).
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    public RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    public ResponseEntity<MunicipalRequest> create(@Valid @RequestBody CreateRequestDto body) {
        MunicipalRequest created = requestService.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public MunicipalRequest getById(@PathVariable String id) {
        return requestService.getById(id);
    }

    @GetMapping
    public List<MunicipalRequest> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        return requestService.list(status, from, to);
    }
}
