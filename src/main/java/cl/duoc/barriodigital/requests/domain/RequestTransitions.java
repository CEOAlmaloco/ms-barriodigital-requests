package cl.duoc.barriodigital.requests.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados EP1.5-01.
 * Define las 8 transiciones permitidas por contrato.
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

    private RequestTransitions() {
    }

    public static boolean isAllowed(RequestStatus from, RequestStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<RequestStatus> next(RequestStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }

    public static Map<RequestStatus, Set<RequestStatus>> asMap() {
        Map<RequestStatus, Set<RequestStatus>> copy = new EnumMap<>(RequestStatus.class);
        ALLOWED.forEach((from, to) -> copy.put(from, Set.copyOf(to)));
        return copy;
    }
}
