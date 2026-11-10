package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.web.CallerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON de la proyección con el ObjectMapper de la aplicación (contrato EP3-04, sección 6.1). */
@JsonTest
class RequestViewJsonTest {

    @Autowired
    ObjectMapper mapper;

    @Test
    void conservaLosCamposYValoresDeLaEntidadIncluidoElFolioYSumaElResponsable() throws Exception {
        MunicipalRequest request = request();

        JsonNode entity = mapper.valueToTree(request);
        JsonNode view = mapper.valueToTree(RequestView.of(request, new CallerContext("func-1", "Funcionario")));

        List<String> entityFields = names(entity);
        List<String> extra = new ArrayList<>(names(view));
        extra.removeAll(entityFields);
        assertTrue(entityFields.contains("folio"));
        assertEquals(List.of("hasAssignee", "assigneeId", "assigneeName"), extra);
        for (String field : entityFields) {
            assertEquals(entity.get(field), view.get(field), field);
        }
        assertFalse(view.get("hasAssignee").booleanValue());
    }

    @Test
    void elAuditorNoRecibeElCorreoDelSolicitante() {
        MunicipalRequest request = request();

        assertEquals(null, RequestView.of(request, new CallerContext("aud-1", "Auditor")).solicitanteEmail());
        assertEquals("vecino@example.com", RequestView.of(request, new CallerContext("func-1", "Funcionario")).solicitanteEmail());
        assertEquals("vecino@example.com", RequestView.of(request, new CallerContext("adm-1", "Admin")).solicitanteEmail());
        assertEquals("vecino@example.com", RequestView.of(request, new CallerContext("vecino-1", "Vecino")).solicitanteEmail());
    }

    @Test
    void elVecinoNoRecibeQuienEsElResponsable() {
        RequestView view = RequestView.of(request(), new CallerContext("vecino-1", "Vecino"));

        assertFalse(view.hasAssignee());
        assertEquals(null, view.assigneeId());
        assertEquals(null, view.assigneeName());
    }

    private static MunicipalRequest request() {
        MunicipalRequest request = new MunicipalRequest("Bache en calle — Calle 1", "Hueco", "bache", "Calle 1",
                "vecino-1", "Ana María", "vecino@example.com", "Bache en calle", Instant.parse("2026-11-10T15:00:00Z"));
        request.assignFolio("BD-2026-000123");
        request.placeLocation("centro", "Frente al poste", -37.87, -71.6, LocationPrecision.EXACTO);
        request.setHasAttachment(true);
        return request;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
