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

    /** Campos del JSON de trámites que el BFF y el frontend ya reciben, en su orden, incluido el folio de EP3-28. */
    private static final List<String> CURRENT_FIELDS = List.of(
            "id", "folio", "title", "description", "procedureType", "procedureLabel", "address", "sector",
            "locationReference", "latitude", "longitude", "locationPrecision", "solicitanteId", "solicitanteNombre",
            "solicitanteEmail", "status", "rejectionReason", "hasAttachment", "createdAt", "updatedAt");

    @Test
    void conservaLosCamposYValoresActualesIncluidoElFolioYSumaResponsableYPlazoAlFinal() throws Exception {
        MunicipalRequest request = request();
        request.assignTo("oid-func", "Ana Funcionaria");

        JsonNode entity = mapper.valueToTree(request);
        JsonNode view = mapper.valueToTree(RequestView.of(request, new CallerContext("func-1", "Funcionario")));

        List<String> expected = new ArrayList<>(CURRENT_FIELDS);
        expected.addAll(List.of(
                "hasAssignee", "assigneeId", "assigneeName", "slaBusinessDays",
                "satisfactionScore", "satisfactionComment"));
        assertEquals(expected, names(view));
        assertTrue(view.get("satisfactionScore").isNull());
        assertTrue(view.get("satisfactionComment").isNull());
        for (String field : CURRENT_FIELDS) {
            assertEquals(entity.get(field), view.get(field), field);
        }
        assertEquals("BD-2026-000123", view.get("folio").asText());
        assertTrue(view.get("hasAssignee").booleanValue());
        assertEquals("oid-func", view.get("assigneeId").asText());
        assertEquals("Ana Funcionaria", view.get("assigneeName").asText());
        assertEquals(5, view.get("slaBusinessDays").intValue());
    }

    @Test
    void losCuatroRolesVenElPlazoYSinPlazoVaNull() {
        MunicipalRequest request = request();
        for (String role : List.of("Vecino", "Funcionario", "Admin", "Auditor")) {
            assertEquals(5, RequestView.of(request, new CallerContext("oid-1", role)).slaBusinessDays(), role);
        }

        request.snapshotSlaBusinessDays(null);
        JsonNode view = mapper.valueToTree(RequestView.of(request, new CallerContext("vecino-1", "Vecino")));
        assertTrue(view.has("slaBusinessDays"));
        assertTrue(view.get("slaBusinessDays").isNull());
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
    void elVecinoSabeQueHayResponsablePeroNoQuienEs() {
        MunicipalRequest request = request();
        assertFalse(RequestView.of(request, new CallerContext("vecino-1", "Vecino")).hasAssignee());

        request.assignTo("oid-func", "Ana Funcionaria");
        RequestView vecino = RequestView.of(request, new CallerContext("vecino-1", "Vecino"));
        RequestView auditor = RequestView.of(request, new CallerContext("aud-1", "Auditor"));

        assertTrue(vecino.hasAssignee());
        assertEquals(null, vecino.assigneeId());
        assertEquals(null, vecino.assigneeName());
        assertEquals("oid-func", auditor.assigneeId());
        assertEquals("Ana Funcionaria", auditor.assigneeName());
    }

    private static MunicipalRequest request() {
        MunicipalRequest request = new MunicipalRequest("Bache en calle — Calle 1", "Hueco", "bache", "Calle 1",
                "vecino-1", "Ana María", "vecino@example.com", "Bache en calle", Instant.parse("2026-11-10T15:00:00Z"));
        request.assignFolio("BD-2026-000123");
        request.placeLocation("centro", "Frente al poste", -37.87, -71.6, LocationPrecision.EXACTO);
        request.setHasAttachment(true);
        request.snapshotSlaBusinessDays(5);
        return request;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
