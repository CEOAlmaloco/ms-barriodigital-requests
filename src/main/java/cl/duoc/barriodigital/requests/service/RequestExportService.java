package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.RequestView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.jpa.HibernateHints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Exportación del listado (EP3-63): mismos filtros, visibilidad y proyección por rol que GET /api/requests.
 * Lee con una sola consulta y la recorre por lotes, así el conteo y las filas salen de la misma instantánea.
 */
@Service
public class RequestExportService {

    static final int EXPORT_MAX_ROWS = 5000;
    static final int EXPORT_BATCH_SIZE = 500;

    private final EntityManager entityManager;
    private final RequestService requests;
    private final int batchSize;

    @Autowired
    public RequestExportService(EntityManager entityManager, RequestService requests) {
        this(entityManager, requests, EXPORT_BATCH_SIZE);
    }

    RequestExportService(EntityManager entityManager, RequestService requests, int batchSize) {
        this.entityManager = entityManager;
        this.requests = requests;
        this.batchSize = batchSize;
    }

    /**
     * Trámites visibles para quien llama, ya proyectados. Sobre el tope responde 400 sin entregar filas: un CSV
     * recortado se leería como completo.
     */
    @Transactional(readOnly = true)
    public List<RequestView> export(RequestListQuery params, CallerContext caller) {
        Specification<MunicipalRequest> spec = requests.exportSpecification(params, caller);
        List<RequestView> rows = new ArrayList<>();
        // Un solo SELECT: Oracle entrega todas sus filas con la consistencia de lectura de su inicio, aunque se
        // lean por lotes. Paginar con varias consultas repetiría u omitiría filas ante escrituras concurrentes.
        try (Stream<MunicipalRequest> stream = query(spec)) {
            Iterator<MunicipalRequest> cursor = stream.iterator();
            while (cursor.hasNext()) {
                if (rows.size() == EXPORT_MAX_ROWS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "La exportacion supera " + EXPORT_MAX_ROWS + " tramites. Agrega filtros");
                }
                rows.add(RequestView.of(cursor.next(), caller));
                if (rows.size() % batchSize == 0) {
                    entityManager.clear();
                    afterBatch(rows.size());
                }
            }
        }
        return rows;
    }

    /** Sin efecto en producción. Los tests de concurrencia escriben aquí entre dos lotes. */
    void afterBatch(int rowsRead) {
    }

    private Stream<MunicipalRequest> query(Specification<MunicipalRequest> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<MunicipalRequest> criteria = cb.createQuery(MunicipalRequest.class);
        Root<MunicipalRequest> root = criteria.from(MunicipalRequest.class);
        Predicate predicate = spec.toPredicate(root, criteria, cb);
        if (predicate != null) {
            criteria.where(predicate);
        }
        // Una fila más que el tope basta para saber que se pasa, sin leer el resto.
        return entityManager.createQuery(criteria)
                .setMaxResults(EXPORT_MAX_ROWS + 1)
                .setHint(HibernateHints.HINT_FETCH_SIZE, batchSize)
                .setHint(HibernateHints.HINT_READ_ONLY, true)
                .getResultStream();
    }
}
