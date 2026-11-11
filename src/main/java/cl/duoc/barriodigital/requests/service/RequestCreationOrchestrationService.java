package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.StoredInstant;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Único punto de entrada de las altas, la JSON y la multipart (contrato EP3-05, sección 1.3).
 * No abre transacción: prepara la fila del año en una transacción corta y después llama al alta,
 * para que el alta no ocupe dos conexiones del pool ni tenga un bloqueo mientras corre la otra.
 */
@Service
public class RequestCreationOrchestrationService {

    private final Clock clock;
    private final FolioCounterService folios;
    private final RequestService requests;

    public RequestCreationOrchestrationService(Clock clock, FolioCounterService folios, RequestService requests) {
        this.clock = clock;
        this.folios = folios;
        this.requests = requests;
    }

    /**
     * Lee el reloj una sola vez. De ese instante salen created_at, el año del folio y la marca CREADO.
     * La imagen llega ya saneada.
     */
    public MunicipalRequest create(
            CreateRequestDto body,
            CallerContext caller,
            ImageAttachmentSanitizer.SanitizedImage image
    ) {
        Instant now = StoredInstant.of(clock.instant());
        try {
            folios.ensureYear(Folio.yearOf(now));
        } catch (DataIntegrityViolationException ex) {
            // Otra alta creó la fila del año al mismo tiempo: la fila ya existe y el alta sigue.
        }
        return requests.create(body, caller, image, now);
    }
}
