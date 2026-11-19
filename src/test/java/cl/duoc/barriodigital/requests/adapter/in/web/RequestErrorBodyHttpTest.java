package cl.duoc.barriodigital.requests.adapter.in.web;

import cl.duoc.barriodigital.requests.TestFolios;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.integration.RequestsH2Application;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuerpo de error que reciben el BFF y el frontend, sobre HTTP real. Los mensajes, status, error y path son los que
 * devolvía Spring Boot antes de EP3-67, registrados con el código anterior: el único campo nuevo es code.
 */
@SpringBootTest(classes = RequestErrorBodyHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:error-body-http;DB_CLOSE_DELAY=-1")
@ActiveProfiles("h2-integration")
class RequestErrorBodyHttpTest {

    private static final String LONG = "x".repeat(501);
    private static final String NO_USER = "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT.";
    private static final String BODY_INVALID = "Validation failed for object='changeStatusDto'. Error count: 1";
    /** Los códigos de admisión del BFF (ms-barriodigital-bff, AdmissionException.Code). */
    private static final Set<String> BFF_CODES = Set.of("ADMISSION_DEPENDENCY_UNAVAILABLE",
            "ADMISSION_DEPENDENCY_INVALID_RESPONSE", "QUOTA_EXHAUSTED", "PROCEDURE_TYPE_UNAVAILABLE",
            "QUOTA_RESERVATION_UNCONFIRMED", "ADMISSION_RESULT_UNCONFIRMED", "ADMISSION_RESPONSE_UNAVAILABLE",
            "QUOTA_COMPENSATION_UNCONFIRMED");

    @Autowired TestRestTemplate http;
    @Autowired MunicipalRequestRepository repository;
    @Autowired ObjectMapper mapper;

    @Test
    void cadaErrorDelCambioDeEstadoYLaAdmisionTraeSuCodeConElMensajeDeSiempre() throws Exception {
        String ingresado = create(RequestStatus.INGRESADO).getId();
        String resuelto = create(RequestStatus.RESUELTO).getId();
        String status = "/api/requests/" + ingresado + "/status";
        String closed = "/api/requests/" + resuelto + "/status";
        String missing = "/api/requests/no-existe/status";
        String admit = "{\"status\":\"ADMITIDO\"}";

        List<Case> cases = List.of(
                new Case(put(status, null, "Funcionario", admit), status, 400, NO_USER, "USER_ID_REQUIRED"),
                new Case(put(status, "func", null, admit), status, 403,
                        "No tienes un rol asignado en BarrioDigital", "ROLE_REQUIRED"),
                new Case(put(status, "func", "Funcionario,Admin", admit), status, 403,
                        "El usuario debe tener un solo rol", "SINGLE_ROLE_REQUIRED"),
                new Case(put(status, "vecino", "Vecino", admit), status, 403,
                        "Solo un Funcionario puede cambiar el estado", "STATUS_CHANGE_FORBIDDEN"),
                new Case(put(status, "func", "Funcionario", "{}"), status, 400,
                        "status es obligatorio", "STATUS_REQUIRED"),
                new Case(put(status, "func", "Funcionario", "{\"status\":\"OTRO\"}"), status, 400,
                        "status invalido. Valores: INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO",
                        "STATUS_INVALID"),
                new Case(put(status, "func", "Funcionario", "{\"status\":\"CANCELADO\"}"), status, 400,
                        "La cancelacion del titular usa POST /api/requests/{id}/cancel",
                        "CANCEL_REQUIRES_CANCEL_ENDPOINT"),
                new Case(put(status, "func", "Funcionario", "{\"status\":\"RECHAZADO\"}"), status, 400,
                        "rejectionReason es obligatorio cuando el estado es RECHAZADO", "REJECTION_REASON_REQUIRED"),
                new Case(put(status, "admin", "Admin", admit), status, 400,
                        "interventionReason es obligatorio para el Admin", "INTERVENTION_REASON_REQUIRED"),
                // @Size del DTO responde antes que el servicio: el texto es el de Spring, no el del servicio.
                new Case(put(status, "func", "Funcionario",
                        "{\"status\":\"RECHAZADO\",\"rejectionReason\":\"" + LONG + "\"}"), status, 400,
                        BODY_INVALID, "REQUEST_BODY_INVALID"),
                new Case(put(status, "admin", "Admin",
                        "{\"status\":\"ADMITIDO\",\"interventionReason\":\"" + LONG + "\"}"), status, 400,
                        BODY_INVALID, "REQUEST_BODY_INVALID"),
                new Case(put(status, "func", "Funcionario", "{\"status\":"), status, 400,
                        "JSON parse error: Unexpected end-of-input within/between Object entries",
                        "REQUEST_BODY_INVALID"),
                new Case(put(missing, "func", "Funcionario", admit), missing, 404,
                        "Tramite no encontrado", "REQUEST_NOT_FOUND"),
                new Case(put(status, "otro", "Funcionario", admit), status, 403,
                        "Solo el responsable del tramite puede operarlo", "NOT_ASSIGNEE"),
                new Case(put(closed, "func", "Funcionario", admit), closed, 409,
                        "No se puede pasar de RESUELTO a ADMITIDO", "STATUS_TRANSITION_NOT_ALLOWED"),
                // GET previo de la admisión en el BFF.
                new Case(get("/api/requests/no-existe", "func", "Funcionario"), "/api/requests/no-existe", 404,
                        "Tramite no encontrado", "REQUEST_NOT_FOUND"),
                new Case(get("/api/requests/" + ingresado, "otro-vecino", "Vecino"), "/api/requests/" + ingresado,
                        404, "Tramite no encontrado", "REQUEST_NOT_FOUND"),
                new Case(get("/api/requests/" + ingresado, null, "Funcionario"), "/api/requests/" + ingresado, 400,
                        NO_USER, "USER_ID_REQUIRED")
        );

        Set<RequestsErrorCode> seen = EnumSet.noneOf(RequestsErrorCode.class);
        for (Case c : cases) {
            assertBody(c);
            seen.add(RequestsErrorCode.valueOf(c.code()));
        }
        // Los dos TOO_LONG del servicio no se alcanzan por HTTP; los cubre RequestServiceTest.
        assertEquals(EnumSet.complementOf(EnumSet.of(RequestsErrorCode.REJECTION_REASON_TOO_LONG,
                RequestsErrorCode.INTERVENTION_REASON_TOO_LONG)), seen);
    }

    @Test
    void lasGestionesUsanLosMismosCodigosQueElCambioDeEstado() throws Exception {
        String id = create(RequestStatus.ADMITIDO).getId();
        String path = "/api/requests/" + id + "/gestiones";
        String missing = "/api/requests/no-existe/gestiones";
        String gestion = "{\"channel\":\"LLAMADA\",\"result\":\"NO_CONTESTA\"}";

        assertBody(new Case(post(missing, "func", "Funcionario", gestion), missing, 404,
                "Tramite no encontrado", "REQUEST_NOT_FOUND"));
        assertBody(new Case(post(path, "otro", "Funcionario", gestion), path, 403,
                "Solo el responsable del tramite puede operarlo", "NOT_ASSIGNEE"));
        assertBody(new Case(post(path, "admin", "Admin", gestion), path, 400,
                "interventionReason es obligatorio para el Admin", "INTERVENTION_REASON_REQUIRED"));
    }

    @Test
    void unErrorSinCodigoConservaElCuerpoSinCode() throws Exception {
        String id = create(RequestStatus.ADMITIDO).getId();
        String path = "/api/requests/" + id + "/cancel";
        ResponseEntity<String> response = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(headers("func", "Funcionario")), String.class);

        assertBody(new Case(response, path, 403, "Solo el Vecino titular puede cancelar el tramite", null));
    }

    @Test
    void losCodigosNoRepitenLosDeAdmisionDelBff() {
        for (RequestsErrorCode code : RequestsErrorCode.values()) {
            assertTrue(!BFF_CODES.contains(code.name()), code.name());
        }
    }

    private void assertBody(Case c) throws Exception {
        JsonNode body = mapper.readTree(c.response().getBody());
        String label = c.path() + " " + c.message();
        Set<String> expected = new HashSet<>(Set.of("timestamp", "status", "error", "message", "path"));
        if (c.code() != null) {
            expected.add("code");
        }
        assertEquals(c.status(), c.response().getStatusCode().value(), label);
        assertEquals(MediaType.APPLICATION_JSON, c.response().getHeaders().getContentType(), label);
        assertEquals(expected, fields(body), label);
        assertEquals(c.status(), body.get("status").asInt(), label);
        assertEquals(HttpStatus.valueOf(c.status()).getReasonPhrase(), body.get("error").asText(), label);
        assertEquals(c.message(), body.get("message").asText(), label);
        assertEquals(c.path(), body.get("path").asText(), label);
        // Mismo formato que escribía Spring Boot, por ejemplo 2026-11-15T03:04:55.136+00:00.
        assertTrue(body.get("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00"),
                label);
        if (c.code() != null) {
            assertEquals(c.code(), body.get("code").asText(), label);
        }
    }

    private static Set<String> fields(JsonNode body) {
        Set<String> names = new HashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private MunicipalRequest create(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino");
        request.assignFolio(TestFolios.unique());
        request.assignTo("func", "Funcionario");
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private ResponseEntity<String> put(String path, String userId, String roles, String json) {
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(json, headers(userId, roles)), String.class);
    }

    private ResponseEntity<String> post(String path, String userId, String roles, String json) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers(userId, roles)), String.class);
    }

    private ResponseEntity<String> get(String path, String userId, String roles) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(userId, roles)), String.class);
    }

    private static HttpHeaders headers(String userId, String roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) headers.set(CallerContext.HEADER_USER_ID, userId);
        if (roles != null) headers.set(CallerContext.HEADER_USER_ROLES, roles);
        return headers;
    }

    private record Case(ResponseEntity<String> response, String path, int status, String message, String code) {
    }

    @Configuration
    @Import(RequestsH2Application.class)
    static class Application {
    }
}
