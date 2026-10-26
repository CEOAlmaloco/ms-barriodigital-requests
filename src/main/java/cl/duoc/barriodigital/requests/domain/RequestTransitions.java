package cl.duoc.barriodigital.requests.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cambios de estado permitidos de un trámite.
 * INGRESADO va a ADMITIDO o RECHAZADO, ADMITIDO a EN_GESTION o RECHAZADO, EN_GESTION a EN_TERRENO o RECHAZADO y EN_TERRENO a RESUELTO o RECHAZADO. RESUELTO y RECHAZADO no continúan.
 */
public final class RequestTransitions {

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = new EnumMap<>(RequestStatus.class);

    static {
        ALLOWED.put(RequestStatus.INGRESADO, EnumSet.of(RequestStatus.ADMITIDO, RequestStatus.RECHAZADO));
        ALLOWED.put(RequestStatus.ADMITIDO, EnumSet.of(RequestStatus.EN_GESTION, RequestStatus.RECHAZADO));
        ALLOWED.put(RequestStatus.EN_GESTION, EnumSet.of(RequestStatus.EN_TERRENO, RequestStatus.RECHAZADO));
        ALLOWED.put(RequestStatus.EN_TERRENO, EnumSet.of(RequestStatus.RESUELTO, RequestStatus.RECHAZADO));
        ALLOWED.put(RequestStatus.RESUELTO, EnumSet.noneOf(RequestStatus.class));
        ALLOWED.put(RequestStatus.RECHAZADO, EnumSet.noneOf(RequestStatus.class));
    }

    /** No se instancia: solo expone las transiciones. */
    private RequestTransitions() {
    }

    /** Indica si se puede pasar de un estado al otro. */
    public static boolean isAllowed(RequestStatus from, RequestStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Devuelve los estados a los que se puede pasar desde este. */
    public static Set<RequestStatus> next(RequestStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }

    /** Copia el mapa de transiciones permitidas. */
    public static Map<RequestStatus, Set<RequestStatus>> asMap() {
        Map<RequestStatus, Set<RequestStatus>> copy = new EnumMap<>(RequestStatus.class);
        ALLOWED.forEach((from, to) -> copy.put(from, Set.copyOf(to)));
        return copy;
    }

    /** Devuelve las transiciones con el nombre de cada estado. Los finales quedan con lista vacía. */
    public static Map<String, List<String>> asNamesMap() {
        Map<String, List<String>> transitions = new LinkedHashMap<>();
        for (RequestStatus status : RequestStatus.values()) {
            List<String> next = ALLOWED.getOrDefault(status, Set.of()).stream()
                    .map(RequestStatus::name)
                    .toList();
            transitions.put(status.name(), next);
        }
        return transitions;
    }
}