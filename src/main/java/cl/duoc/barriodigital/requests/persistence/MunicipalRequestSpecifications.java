package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Filtros y orden del listado de trámites (EP3-30). Cada filtro con valor null no se aplica y se combinan por un Y lógico.
 * El orden también va como Specification, para usar el CASE del ciclo de vida, y el PageRequest va sin orden.
 */
public final class MunicipalRequestSpecifications {

    /** Carácter de escape del LIKE, para buscar %, _ y \ como texto. */
    private static final char LIKE_ESCAPE = '\\';

    /** Campos por los que se puede ordenar, con su nombre en la API. */
    public enum SortField {
        CREATED_AT("createdAt"),
        STATUS("status"),
        FOLIO("folio");

        private final String apiName;

        SortField(String apiName) {
            this.apiName = apiName;
        }

        /** Busca el campo por su nombre en la API. Devuelve null si no existe. */
        public static SortField fromApiName(String name) {
            return Arrays.stream(values()).filter(field -> field.apiName.equals(name)).findFirst().orElse(null);
        }
    }

    private MunicipalRequestSpecifications() {
    }

    public static Specification<MunicipalRequest> statusIs(RequestStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    /** true deja los trámites abiertos y false los cerrados (RESUELTO o RECHAZADO). */
    public static Specification<MunicipalRequest> open(Boolean open) {
        return (root, query, cb) -> {
            if (open == null) {
                return null;
            }
            List<RequestStatus> matching = Arrays.stream(RequestStatus.values())
                    .filter(status -> RequestTransitions.isClosed(status) != open)
                    .toList();
            return root.get("status").in(matching);
        };
    }

    /** Desde ese instante, incluido. */
    public static Specification<MunicipalRequest> createdFrom(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    /** Hasta ese instante, sin incluirlo: cubre días completos sin perder fracciones de segundo. */
    public static Specification<MunicipalRequest> createdBefore(Instant before) {
        return (root, query, cb) -> before == null ? null : cb.lessThan(root.get("createdAt"), before);
    }

    public static Specification<MunicipalRequest> ownedBy(String solicitanteId) {
        return (root, query, cb) -> solicitanteId == null ? null : cb.equal(root.get("solicitanteId"), solicitanteId);
    }

    public static Specification<MunicipalRequest> assignedTo(String assigneeId) {
        return (root, query, cb) -> assigneeId == null ? null : cb.equal(root.get("assigneeId"), assigneeId);
    }

    public static Specification<MunicipalRequest> unassigned(boolean unassigned) {
        return (root, query, cb) -> unassigned ? cb.isNull(root.get("assigneeId")) : null;
    }

    public static Specification<MunicipalRequest> procedureTypeIs(String procedureType) {
        return (root, query, cb) -> procedureType == null ? null : cb.equal(root.get("procedureType"), procedureType);
    }

    public static Specification<MunicipalRequest> sectorIs(String sector) {
        return (root, query, cb) -> sector == null ? null : cb.equal(root.get("sector"), sector);
    }

    /** Busca el texto en folio, descripción o dirección, sin distinguir mayúsculas. */
    public static Specification<MunicipalRequest> matches(String text) {
        return (root, query, cb) -> {
            if (text == null) {
                return null;
            }
            String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("folio")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("description")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("address")), pattern, LIKE_ESCAPE)
            );
        };
    }

    /**
     * Ordena por el campo y desempata por id, para que un trámite no se repita ni se pierda entre páginas.
     * status sigue el ciclo de vida y, dentro de un estado, van primero los más recientes.
     * La consulta de conteo de la página no lleva orden.
     */
    public static Specification<MunicipalRequest> orderedBy(SortField field, boolean ascending) {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class) {
                Expression<?> key = switch (field) {
                    case CREATED_AT -> root.get("createdAt");
                    case FOLIO -> root.get("folio");
                    case STATUS -> lifecycleOrder(root, cb);
                };
                List<Order> orders = new ArrayList<>();
                orders.add(ascending ? cb.asc(key) : cb.desc(key));
                if (field == SortField.STATUS) {
                    orders.add(cb.desc(root.get("createdAt")));
                }
                orders.add(cb.asc(root.get("id")));
                query.orderBy(orders);
            }
            return null;
        };
    }

    /** Posición del estado en RequestStatus, que sigue el ciclo de vida, y no su orden alfabético. */
    private static Expression<Integer> lifecycleOrder(Root<MunicipalRequest> root, CriteriaBuilder cb) {
        CriteriaBuilder.Case<Integer> order = cb.selectCase();
        for (RequestStatus status : RequestStatus.values()) {
            order = order.when(cb.equal(root.get("status"), status), cb.literal(status.ordinal()));
        }
        return order.otherwise(cb.literal(RequestStatus.values().length));
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
