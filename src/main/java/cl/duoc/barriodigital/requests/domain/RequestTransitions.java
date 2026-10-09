package cl.duoc.barriodigital.requests.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Máquina de estados del trámite (docs/arquitectura-barriodigital.md).
 * El front no debe copiar estas flechas: las pide en GET /api/requests/meta/transitions.
 */
public final class RequestTransitions {

    private static final Map<RequestStatus, List<RequestStatus>> NEXT = Map.of(
            RequestStatus.INGRESADO, List.of(RequestStatus.ADMITIDO, RequestStatus.RECHAZADO),
            RequestStatus.ADMITIDO, List.of(RequestStatus.EN_GESTION, RequestStatus.RECHAZADO),
            RequestStatus.EN_GESTION, List.of(RequestStatus.EN_TERRENO),
            RequestStatus.EN_TERRENO, List.of(RequestStatus.RESUELTO, RequestStatus.RECHAZADO),
            RequestStatus.RESUELTO, List.of(),
            RequestStatus.RECHAZADO, List.of()
    );

    private RequestTransitions() {
    }

    /** Un estado por clave, en el orden del enum. Los finales van con lista vacía. */
    public static Map<String, List<String>> asMap() {
        Map<String, List<String>> transitions = new LinkedHashMap<>();
        for (RequestStatus status : RequestStatus.values()) {
            List<String> next = NEXT.getOrDefault(status, List.of()).stream()
                    .map(RequestStatus::name)
                    .toList();
            transitions.put(status.name(), next);
        }
        return transitions;
    }
}
