package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.FolioCounter;
import cl.duoc.barriodigital.requests.persistence.FolioCounterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contador de folios por año (contrato EP3-05, secciones 1.2 y 1.3). Solo JPA, sin SQL nativo.
 * Es un bean propio para que REQUIRES_NEW pase por el proxy de Spring.
 */
@Service
public class FolioCounterService {

    private static final Logger log = LoggerFactory.getLogger(FolioCounterService.class);

    private final FolioCounterRepository counters;

    public FolioCounterService(FolioCounterRepository counters) {
        this.counters = counters;
    }

    /**
     * Deja creada la fila del año, en su propia transacción y antes del alta.
     * No captura excepciones: si dos altas la crean a la vez, la violación de la PK salta en el commit,
     * después de este método, y la captura el orquestador, fuera de toda transacción.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureYear(int year) {
        if (!counters.existsById(year)) {
            counters.save(new FolioCounter(year));
        }
    }

    /**
     * Entrega el folio siguiente del año con la fila bloqueada, dentro de la transacción del alta.
     * Si el alta se revierte, el número vuelve al contador y no queda un hueco.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(int year) {
        FolioCounter counter = counters.findByIdForUpdate(year)
                .orElseThrow(() -> new IllegalStateException("Falta el contador de folio del año " + year));
        if (counter.getLastNumber() >= Folio.MAX_NUMBER) {
            log.error("Se agotaron los folios del año {}: el tope es {}", year, Folio.MAX_NUMBER);
            throw new IllegalStateException("Se agotaron los folios del año " + year);
        }
        return Folio.format(year, counter.takeNext());
    }
}
