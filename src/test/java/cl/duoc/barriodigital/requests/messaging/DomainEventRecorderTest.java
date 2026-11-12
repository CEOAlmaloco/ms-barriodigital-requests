package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El JSON que queda en el outbox cumple el contrato EP3-01 (secciones 4 a 8): sobre sin sequence, payload con
 * todos sus campos aunque sean null, formatos de instante, uuid y enum, y sin correo, dirección ni nota.
 * La transacción y el orden se prueban contra H2 en OutboxH2Test.
 */
class DomainEventRecorderTest {

    private static final String REQUEST_ID = "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc";
    private static final Instant AT = Instant.parse("2026-11-10T13:05:12.481Z");
    private static final UUID TRACE = UUID.fromString("c3a91e22-0d44-4f6b-8a17-55e2b0c4d901");
    private static final ObjectMapper READER = new ObjectMapper();

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final DomainEventRecorder recorder = new DomainEventRecorder(outbox,
            Clock.fixed(Instant.parse("2026-11-10T13:05:13Z"), ZoneOffset.UTC));

    @BeforeEach
    void saveReturnsTheRow() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void requestCreatedConUbicacionYPlazo() throws Exception {
        MunicipalRequest request = request();
        RequestActivity row = RequestActivity.created(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino");

        OutboxEvent saved = recorder.record(request, row, TRACE);

        JsonNode json = envelope(saved, "request.created");
        JSONAssert.assertEquals("""
                {"id": "oid-vecino", "name": "Vecino Test", "role": "Vecino"}""",
                json.get("actor").toString(), JSONCompareMode.STRICT);
        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "procedureType": "bache",
                  "procedureLabel": "Bache en calle",
                  "sector": "ralco",
                  "latitude": -37.9946,
                  "longitude": -71.4962,
                  "status": "INGRESADO",
                  "slaBusinessDays": 5
                }""", json.get("payload").toString(), JSONCompareMode.STRICT);
        assertEquals("2026-11-10T13:05:12.481Z", json.get("occurredAt").asText());
        assertEquals(Instant.parse("2026-11-10T13:05:13Z"), saved.getCreatedAt());
    }

    @Test
    void requestCreatedSinUbicacionNiPlazoLlevaLosCamposEnNullYLaEtiquetaConocida() throws Exception {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "oid-vecino", "Vecino", null, null, AT);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        request.assignFolio("BD-2026-000124");

        JsonNode json = envelope(recorder.record(request,
                RequestActivity.created(REQUEST_ID, AT, "oid-vecino", "Vecino", "Vecino"), TRACE), "request.created");

        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000124",
                  "procedureType": "bache",
                  "procedureLabel": "Bache en calle",
                  "sector": null,
                  "latitude": null,
                  "longitude": null,
                  "status": "INGRESADO",
                  "slaBusinessDays": null
                }""", json.get("payload").toString(), JSONCompareMode.STRICT);
    }

    @Test
    void requestStatusChangedConRechazoEIntervencionDelAdmin() throws Exception {
        RequestActivity row = RequestActivity.statusChanged(REQUEST_ID, AT, "oid-admin", "Admin Test", "Admin",
                RequestStatus.ADMITIDO, RequestStatus.RECHAZADO, "Direccion fuera de la comuna", "Reclamo en la OIRS");

        JsonNode json = envelope(recorder.record(request(), row, TRACE), "request.status-changed");

        assertEquals("Admin", json.get("actor").get("role").asText());
        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "procedureType": "bache",
                  "sector": "ralco",
                  "fromStatus": "ADMITIDO",
                  "toStatus": "RECHAZADO",
                  "reason": "Direccion fuera de la comuna",
                  "interventionReason": "Reclamo en la OIRS"
                }""", json.get("payload").toString(), JSONCompareMode.STRICT);
    }

    @Test
    void requestStatusChangedDelFuncionarioLlevaReasonEInterventionReasonEnNull() throws Exception {
        RequestActivity row = RequestActivity.statusChanged(REQUEST_ID, AT, "oid-func", "Funcionario Test",
                "Funcionario", RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null);

        JsonNode payload = envelope(recorder.record(request(), row, TRACE), "request.status-changed").get("payload");

        assertTrue(payload.has("reason") && payload.get("reason").isNull());
        assertTrue(payload.has("interventionReason") && payload.get("interventionReason").isNull());
    }

    @Test
    void requestCancelledDelTitular() throws Exception {
        RequestActivity row = RequestActivity.statusChanged(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino",
                RequestStatus.INGRESADO, RequestStatus.CANCELADO, null, null);

        OutboxEvent saved = recorder.record(request(), row, TRACE);

        assertEquals(DomainEventRecorder.REQUEST_CANCELLED, saved.getEventType());
        JsonNode json = envelope(saved, "request.cancelled");
        assertEquals("Vecino", json.get("actor").get("role").asText());
        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "procedureType": "bache",
                  "sector": "ralco",
                  "fromStatus": "INGRESADO",
                  "toStatus": "CANCELADO"
                }""", json.get("payload").toString(), JSONCompareMode.STRICT);
        assertFalse(saved.getPayload().contains("reason"));
    }

    @Test
    void requestContactLoggedSinLaNotaLibre() throws Exception {
        RequestActivity row = RequestActivity.gestion(REQUEST_ID, AT, "oid-func", "Funcionario Test", "Funcionario",
                ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "Dejé recado con la vecina del 12",
                Instant.parse("2026-11-11T13:00:00Z"), null);

        OutboxEvent saved = recorder.record(request(), row, TRACE);

        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "channel": "LLAMADA",
                  "result": "NO_CONTESTA",
                  "nextAttemptAt": "2026-11-11T13:00:00Z",
                  "interventionReason": null
                }""", envelope(saved, "request.contact-logged").get("payload").toString(), JSONCompareMode.STRICT);
        assertFalse(saved.getPayload().contains("recado"));
    }

    @Test
    void requestAssignedEnLaPrimeraAsignacionYEnLaReasignacion() throws Exception {
        RequestActivity first = RequestActivity.assigned(REQUEST_ID, AT, "oid-func", "Ana", "Funcionario",
                "oid-func", "Ana", null, null, null);
        RequestActivity reassignment = RequestActivity.assigned(REQUEST_ID, AT, "oid-admin", "Admin Test", "Admin",
                "oid-bruno", "Bruno", "oid-func", "Ana", "Redistribucion de carga del sector");

        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "assigneeId": "oid-func",
                  "assigneeName": "Ana",
                  "previousAssigneeId": null,
                  "reason": null
                }""", envelope(recorder.record(request(), first, TRACE), "request.assigned").get("payload").toString(),
                JSONCompareMode.STRICT);
        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "assigneeId": "oid-bruno",
                  "assigneeName": "Bruno",
                  "previousAssigneeId": "oid-func",
                  "reason": "Redistribucion de carga del sector"
                }""", envelope(recorder.record(request(), reassignment, TRACE), "request.assigned").get("payload")
                .toString(), JSONCompareMode.STRICT);
    }

    @Test
    void requestRatedDelTitular() throws Exception {
        MunicipalRequest request = request();
        request.applyRating(5, "Muy bien");
        RequestActivity row = RequestActivity.rated(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino", 5, "Muy bien");

        OutboxEvent saved = recorder.record(request, row, TRACE);

        assertEquals(DomainEventRecorder.REQUEST_RATED, saved.getEventType());
        JsonNode json = envelope(saved, "request.rated");
        assertEquals("Vecino", json.get("actor").get("role").asText());
        JSONAssert.assertEquals("""
                {
                  "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "folio": "BD-2026-000123",
                  "score": 5,
                  "comment": "Muy bien"
                }""", json.get("payload").toString(), JSONCompareMode.STRICT);
    }

    @Test
    void requestRatedSinComentarioLlevaCommentEnNull() throws Exception {
        MunicipalRequest request = request();
        request.applyRating(1, null);
        RequestActivity row = RequestActivity.rated(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino", 1, null);

        JsonNode payload = envelope(recorder.record(request, row, TRACE), "request.rated").get("payload");

        assertEquals(1, payload.get("score").intValue());
        assertTrue(payload.has("comment") && payload.get("comment").isNull());
    }

    @Test
    void elHitoPublicoDeAsignacionNoGeneraEvento() {
        RequestActivity milestone = RequestActivity.assignmentMilestone(REQUEST_ID, AT);

        assertThrows(IllegalArgumentException.class, () -> recorder.record(request(), milestone, TRACE));
        verifyNoInteractions(outbox);
    }

    @Test
    void sinRolDelActorNoQuedaEvento() {
        RequestActivity row = RequestActivity.created(REQUEST_ID, AT, "oid-vecino", "Vecino", null);

        assertThrows(NullPointerException.class, () -> recorder.record(request(), row, TRACE));
        verifyNoInteractions(outbox);
    }

    @Test
    void ningunEventoLlevaCorreoNiDireccionTextual() {
        List<OutboxEvent> saved = new ArrayList<>();
        saved.add(recorder.record(request(), RequestActivity.created(REQUEST_ID, AT, "v", "V", "Vecino"), TRACE));
        saved.add(recorder.record(request(), RequestActivity.statusChanged(REQUEST_ID, AT, "f", "F", "Funcionario",
                RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null), TRACE));

        for (OutboxEvent event : saved) {
            assertFalse(event.getPayload().contains("vecino@example.com"), event.getEventType());
            assertFalse(event.getPayload().contains("Pasaje Los Aromos"), event.getEventType());
        }
    }

    /** Revisa el sobre común y devuelve el JSON completo. */
    private static JsonNode envelope(OutboxEvent saved, String type) throws Exception {
        JsonNode json = READER.readTree(saved.getPayload());
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "producer", "traceId", "actor",
                "payload"), fields, "sobre sin sequence ni sourceIp");
        assertEquals(saved.getEventId(), json.get("eventId").asText());
        assertTrue(json.get("eventId").asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals(type, json.get("eventType").asText());
        assertEquals(type, saved.getEventType());
        assertEquals(1, json.get("schemaVersion").intValue());
        assertEquals("2026-11-10T13:05:12.481Z", json.get("occurredAt").asText());
        assertEquals("requests", json.get("producer").asText());
        assertEquals(TRACE.toString(), json.get("traceId").asText());
        assertEquals(REQUEST_ID, saved.getAggregateId());
        return json;
    }

    private static MunicipalRequest request() {
        MunicipalRequest request = new MunicipalRequest("Bache en calle — Pasaje Los Aromos 12", "Hoyo profundo",
                "bache", "Pasaje Los Aromos 12", "oid-vecino", "Vecino Test", "vecino@example.com", "Bache en calle",
                AT);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        request.assignFolio("BD-2026-000123");
        request.placeLocation("ralco", null, -37.9946, -71.4962, LocationPrecision.EXACTO);
        request.snapshotSlaBusinessDays(5);
        return request;
    }
}
