package cl.duoc.barriodigital.requests.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestTransitionsTest {

    @Test
    void publicaUnaEntradaPorEstado() {
        Map<String, List<String>> transitions = RequestTransitions.asMap();

        assertEquals(RequestStatus.values().length, transitions.size());
        for (RequestStatus status : RequestStatus.values()) {
            assertEquals(true, transitions.containsKey(status.name()));
        }
    }

    @Test
    void sigueLaMaquinaDelCaso() {
        Map<String, List<String>> transitions = RequestTransitions.asMap();

        assertEquals(List.of("ADMITIDO", "RECHAZADO"), transitions.get("INGRESADO"));
        assertEquals(List.of("EN_GESTION", "RECHAZADO"), transitions.get("ADMITIDO"));
        assertEquals(List.of("EN_TERRENO"), transitions.get("EN_GESTION"));
        assertEquals(List.of("RESUELTO", "RECHAZADO"), transitions.get("EN_TERRENO"));
        assertEquals(List.of(), transitions.get("RESUELTO"));
        assertEquals(List.of(), transitions.get("RECHAZADO"));
    }
}
