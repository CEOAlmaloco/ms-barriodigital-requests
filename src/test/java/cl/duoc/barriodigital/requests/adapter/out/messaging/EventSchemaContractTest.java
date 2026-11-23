package cl.duoc.barriodigital.requests.adapter.out.messaging;

import cl.duoc.barriodigital.requests.adapter.out.persistence.OutboxEventRepository;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El JSON que publica el relay cumple los esquemas de contracts/events. Agregar, quitar o cambiar un campo rompe esta
 * prueba: obliga a actualizar el esquema y a decidir si sube schemaVersion (kafka-topicos.md, sección 11).
 */
class EventSchemaContractTest {

    private static final Path EVENTS = Path.of("contracts", "events");
    private static final String REQUEST_ID = "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc";
    private static final Instant AT = Instant.parse("2026-11-10T13:05:12.481Z");
    private static final UUID TRACE = UUID.fromString("c3a91e22-0d44-4f6b-8a17-55e2b0c4d901");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build();

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final DomainEventRecorder recorder = new DomainEventRecorder(outbox,
            Clock.fixed(Instant.parse("2026-11-10T13:05:13Z"), ZoneOffset.UTC));

    @BeforeEach
    void saveReturnsTheRow() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cadaEventoQuePublicaElRelayCumpleSuEsquema() throws Exception {
        for (Map.Entry<String, List<JsonNode>> entry : published().entrySet()) {
            JsonSchema schema = schema(entry.getKey());
            for (JsonNode event : entry.getValue()) {
                Set<ValidationMessage> errors = schema.validate(event);
                assertTrue(errors.isEmpty(), entry.getKey() + " no cumple su esquema: " + errors);
            }
        }
    }

    @Test
    void cadaEventTypeDelRecorderTieneSuEsquemaYNoSobranEsquemas() throws Exception {
        Set<String> eventTypes = Arrays.stream(DomainEventRecorder.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("REQUEST_"))
                .map(EventSchemaContractTest::constant)
                .collect(Collectors.toSet());
        Set<String> schemas;
        try (Stream<Path> files = Files.list(EVENTS)) {
            schemas = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("request."))
                    .map(name -> name.replace(".v" + DomainEventRecorder.SCHEMA_VERSION + ".schema.json", ""))
                    .collect(Collectors.toSet());
        }

        assertEquals(eventTypes, schemas);
        assertEquals(eventTypes, published().keySet());
    }

    @Test
    void unCampoRenombradoUnNullEnUnCampoObligatorioOUnaSolaCoordenadaRompenElEsquema() throws Exception {
        ObjectNode created = (ObjectNode) published().get(DomainEventRecorder.REQUEST_CREATED).get(0);
        JsonSchema schema = schema(DomainEventRecorder.REQUEST_CREATED);

        ObjectNode renamed = created.deepCopy();
        ((ObjectNode) renamed.get("payload")).set("folioNumber", ((ObjectNode) renamed.get("payload")).remove("folio"));
        ObjectNode nullFolio = created.deepCopy();
        ((ObjectNode) nullFolio.get("payload")).putNull("folio");
        ObjectNode oneCoordinate = created.deepCopy();
        ((ObjectNode) oneCoordinate.get("payload")).putNull("longitude");
        ObjectNode withSourceIp = created.deepCopy();
        ((ObjectNode) withSourceIp.get("actor")).put("sourceIp", "203.0.113.25");

        for (ObjectNode broken : List.of(renamed, nullFolio, oneCoordinate, withSourceIp)) {
            assertFalse(schema.validate(broken).isEmpty(), "el esquema aceptó " + broken);
        }
    }

    private Map<String, List<JsonNode>> published() throws Exception {
        MunicipalRequest rated = request();
        rated.applyRating(5, "Muy bien");
        MunicipalRequest withoutLocation = request();
        ReflectionTestUtils.setField(withoutLocation, "latitude", null);
        ReflectionTestUtils.setField(withoutLocation, "longitude", null);
        ReflectionTestUtils.setField(withoutLocation, "sector", null);

        Map<String, List<JsonNode>> events = new LinkedHashMap<>();
        events.put(DomainEventRecorder.REQUEST_CREATED, List.of(
                publish(request(), RequestActivity.created(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino")),
                publish(withoutLocation, RequestActivity.created(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino"))));
        events.put(DomainEventRecorder.REQUEST_STATUS_CHANGED, List.of(
                publish(request(), RequestActivity.statusChanged(REQUEST_ID, AT, "oid-admin", "Admin Test", "Admin",
                        RequestStatus.ADMITIDO, RequestStatus.RECHAZADO, "Direccion fuera de la comuna",
                        "Reclamo en la OIRS")),
                publish(request(), RequestActivity.statusChanged(REQUEST_ID, AT, "oid-func", "Funcionario Test",
                        "Funcionario", RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null))));
        events.put(DomainEventRecorder.REQUEST_CANCELLED, List.of(
                publish(request(), RequestActivity.statusChanged(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino",
                        RequestStatus.INGRESADO, RequestStatus.CANCELADO, null, null))));
        events.put(DomainEventRecorder.REQUEST_CONTACT_LOGGED, List.of(
                publish(request(), RequestActivity.gestion(REQUEST_ID, AT, "oid-func", "Funcionario Test",
                        "Funcionario", ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "Nota interna",
                        Instant.parse("2026-11-11T13:00:00Z"), null))));
        events.put(DomainEventRecorder.REQUEST_ASSIGNED, List.of(
                publish(request(), RequestActivity.assigned(REQUEST_ID, AT, "oid-func", "Ana", "Funcionario",
                        "oid-func", "Ana", null, null, null)),
                publish(request(), RequestActivity.assigned(REQUEST_ID, AT, "oid-admin", "Admin Test", "Admin",
                        "oid-bruno", "Bruno", "oid-func", "Ana", "Redistribucion de carga del sector"))));
        events.put(DomainEventRecorder.REQUEST_RATED, List.of(
                publish(rated, RequestActivity.rated(REQUEST_ID, AT, "oid-vecino", "Vecino Test", "Vecino", 5,
                        "Muy bien"))));
        events.put(DomainEventRecorder.REQUEST_CITIZEN_COMMENT, List.of(
                publish(request(), RequestActivity.citizenComment(REQUEST_ID, AT, "oid-vecino", "Vecino Test",
                        "Vecino", "Texto del vecino", true))));
        return events;
    }

    /** El JSON tal como sale a Kafka: el del outbox más la sequence que agrega el relay. */
    private JsonNode publish(MunicipalRequest request, RequestActivity row) throws IOException {
        OutboxEvent saved = recorder.record(request, row, TRACE);
        return JSON.readTree(OutboxRelay.withSequence(saved.getPayload(), 41L));
    }

    private static JsonSchema schema(String eventType) {
        Path file = EVENTS.resolve(eventType + ".v" + DomainEventRecorder.SCHEMA_VERSION + ".schema.json");
        return FACTORY.getSchema(SchemaLocation.of(file.toAbsolutePath().toUri().toString()), CONFIG);
    }

    private static String constant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
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
