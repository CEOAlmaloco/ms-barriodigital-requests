package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class RequestService {

    private final MunicipalRequestRepository repository;

    public RequestService(MunicipalRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MunicipalRequest create(CreateRequestDto body) {
        MunicipalRequest request = new MunicipalRequest(
                body.title().trim(),
                body.description().trim(),
                body.procedureType().trim()
        );
        return repository.save(request);
    }

    @Transactional(readOnly = true)
    public MunicipalRequest getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
    }

    @Transactional(readOnly = true)
    public List<MunicipalRequest> list(RequestStatus status, Instant from, Instant to) {
        return repository.search(status, from, to);
    }
}
