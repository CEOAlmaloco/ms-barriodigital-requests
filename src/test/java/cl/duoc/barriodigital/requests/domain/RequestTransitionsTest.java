package cl.duoc.barriodigital.requests.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestTransitionsTest {

    @Test
    void publicaUnaEntradaPorEstado() {
        Map<String, List<String>> transitions = RequestTransitions.asNamesMap();

        assertEquals(RequestStatus.values().length, transitions.size());
        for (RequestStatus status : RequestStatus.values()) {
            assertEquals(true, transitions.containsKey(status.name()));
        }
    }

    @Test
    void sigueLaMaquinaDelCaso() {
        Map<RequestStatus, Set<RequestStatus>> transitions = RequestTransitions.asMap();
        assertEquals(Map.of(
                RequestStatus.INGRESADO, Set.of(RequestStatus.ADMITIDO, RequestStatus.RECHAZADO),
                RequestStatus.ADMITIDO, Set.of(RequestStatus.EN_GESTION, RequestStatus.RECHAZADO),
                RequestStatus.EN_GESTION, Set.of(RequestStatus.EN_TERRENO, RequestStatus.RECHAZADO),
                RequestStatus.EN_TERRENO, Set.of(RequestStatus.RESUELTO, RequestStatus.RECHAZADO),
                RequestStatus.RESUELTO, Set.of(),
                RequestStatus.RECHAZADO, Set.of()
        ), transitions);
        Map<String, List<String>> names = RequestTransitions.asNamesMap();
        transitions.forEach((state, targets) -> {
            List<String> actual = names.get(state.name());
            assertEquals(targets.size(), actual.size());
            assertEquals(targets.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                    Set.copyOf(actual));
        });
    }
}
