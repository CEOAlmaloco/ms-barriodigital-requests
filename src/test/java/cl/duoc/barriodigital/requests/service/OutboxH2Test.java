package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssignRequestDto;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import cl.duoc.barriodigital.requests.web.dto.RegisterGestionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Outbox contra JPA real en H2, con commits reales (contrato EP3-02, secciones 2.2 y 2.3): el evento queda o se
 * pierde junto con el cambio y su marca, un evento por operación, sequence creciente y el traceId de RabbitMQ.
 * La tabla la crea db/h2-outbox-events.sql, como en Oracle; no valida el ORDER del identity de Oracle.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestService.class, RequestActivityService.class, RequestAssignmentService.class, StaffMemberService.class,
        FolioCounterService.class, DomainEventRecorder.class, ClockConfig.class, NotificationPreferenceService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OutboxH2Test {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
    private static final CallerContext VECINO =
            new CallerContext("oid-vecino", "Vecino", "Vecino%20Test", "vecino@example.com", "Bache%20en%20calle", "5");
    private static final CallerContext ANA = new CallerContext("oid-ana", "Funcionario", "Ana");
    private static final CallerContext ADMIN = new CallerContext("oid-admin", "Admin", "Admin");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired RequestService requests;
    @Autowired RequestActivityService activityService;
    @Autowired RequestAssignmentService assignment;
    @Autowired StaffMemberService staff;
    @Autowired FolioCounterService folios;
    @Autowired DomainEventRecorder recorder;
    @Autowired MunicipalRequestRepository repository;
    @Autowired RequestActivityRepository activity;
    @Autowired OutboxEventRepository outbox;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockBean EventPublisher publisher;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        activity.deleteAll();
        repository.deleteAll();
        folios.ensureYear(Folio.yearOf(NOW));
        staff.touch("oid-ana", "Ana", "Funcionario");
        staff.touch("oid-bruno", "Bruno", "Funcionario");
    }

    @Test
    void elAltaDejaRequestCreatedConElRolElPlazoYElTraceIdDeRabbitMq() throws Exception {
        MunicipalRequest created = create();

        List<OutboxEvent> events = events(created.getId());
        assertEquals(List.of(DomainEventRecorder.REQUEST_CREATED), types(events));
        JsonNode json = JSON.readTree(events.get(0).getPayload());
        assertEquals("Vecino", json.get("actor").get("role").asText());
        assertEquals("Vecino Test", json.get("actor").get("name").asText());
        assertEquals(5, json.get("payload").get("slaBusinessDays").intValue());
        assertEquals(created.getCreatedAt().toString(), json.get("occurredAt").asText());
        assertEquals(5, repository.findById(created.getId()).orElseThrow().getSlaBusinessDays());
        assertEquals("Vecino", rows(created.getId()).get(0).getActorRole());

        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        verify(publisher).publish(eq("certificate.receipt"), eq(created.getId()), any(), trace.capture());
        assertEquals(trace.getValue().toString(), json.get("traceId").asText());
    }

    @Test
    void dosOperacionesSobreElMismoTramiteDanSequenceCrecientes() throws Exception {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));
        inTransaction(() -> requests.changeStatus(created.getId(), "ADMITIDO", null, null, ANA));
        inTransaction(() -> requests.changeStatus(created.getId(), "EN_GESTION", null, null, ANA));

        List<OutboxEvent> events = events(created.getId());
        assertEquals(List.of("request.created", "request.assigned", "request.status-changed",
                "request.status-changed"), types(events));
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).getEventSequence() > events.get(i - 1).getEventSequence());
        }
        JsonNode admitted = JSON.readTree(events.get(2).getPayload());
        assertEquals("ADMITIDO", admitted.get("payload").get("toStatus").asText());
        assertEquals("Funcionario", admitted.get("actor").get("role").asText());
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        verify(publisher).publish(eq("email.admitted"), eq(created.getId()), any(), trace.capture());
        assertEquals(trace.getValue().toString(), admitted.get("traceId").asText());
    }

    @Test
    void unaAsignacionDejaUnSoloEventoAunqueGuardeElHitoPublico() throws Exception {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));

        long marks = rows(created.getId()).stream().filter(row -> row.getKind() == ActivityKind.ASIGNACION).count();
        assertEquals(2, marks);
        assertEquals(1, assignedEvents(created.getId()).size());

        inTransaction(() -> assignment.assign(created.getId(), new AssignRequestDto("oid-bruno", "Carga del sector"),
                ADMIN));

        List<OutboxEvent> assigned = assignedEvents(created.getId());
        assertEquals(2, assigned.size());
        JsonNode reassignment = JSON.readTree(assigned.get(1).getPayload());
        assertEquals("Admin", reassignment.get("actor").get("role").asText());
        assertEquals("oid-ana", reassignment.get("payload").get("previousAssigneeId").asText());
        assertEquals("Carga del sector", reassignment.get("payload").get("reason").asText());
    }

    @Test
    void laGestionDejaRequestContactLoggedConElRolDelActor() throws Exception {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));
        inTransaction(() -> activityService.registerGestion(created.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "Nota interna", null), ANA));

        List<OutboxEvent> events = events(created.getId());
        OutboxEvent logged = events.get(events.size() - 1);
        assertEquals(DomainEventRecorder.REQUEST_CONTACT_LOGGED, logged.getEventType());
        assertFalse(logged.getPayload().contains("Nota interna"));
        RequestActivity gestion = rows(created.getId()).stream()
                .filter(row -> row.getKind() == ActivityKind.GESTION).findFirst().orElseThrow();
        assertEquals("Funcionario", gestion.getActorRole());
        // El instante se trunca a microsegundos al nacer (StoredInstant): la base y el evento coinciden exacto.
        assertEquals(gestion.getOccurredAt(),
                Instant.parse(JSON.readTree(logged.getPayload()).get("occurredAt").asText()));
    }

    @Test
    void laCalificacionDelTitularDejaRequestRated() throws Exception {
        MunicipalRequest created = create();
        inTransaction(() -> {
            MunicipalRequest found = repository.findByIdForUpdate(created.getId()).orElseThrow();
            found.applyStatus(RequestStatus.RESUELTO, null);
            repository.save(found);
        });

        inTransaction(() -> requests.rate(created.getId(), 5, "Bien", VECINO));

        List<OutboxEvent> events = events(created.getId());
        assertEquals(List.of(DomainEventRecorder.REQUEST_CREATED, DomainEventRecorder.REQUEST_RATED), types(events));
        assertEquals(5, repository.findById(created.getId()).orElseThrow().getSatisfactionScore());
        JsonNode rated = JSON.readTree(events.get(1).getPayload());
        assertEquals(5, rated.get("payload").get("score").intValue());
        assertEquals("Bien", rated.get("payload").get("comment").asText());
        assertEquals("Vecino", rated.get("actor").get("role").asText());
        assertEquals(1, rows(created.getId()).stream()
                .filter(row -> row.getKind() == ActivityKind.CALIFICACION)
                .count());
    }

    @Test
    void unRollbackNoDejaNiLaActividadNiElEvento() {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));
        int rowsBefore = rows(created.getId()).size();
        int eventsBefore = events(created.getId()).size();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            requests.changeStatus(created.getId(), "ADMITIDO", null, null, ANA);
            tx.setRollbackOnly();
        });

        assertEquals(RequestStatus.INGRESADO, repository.findById(created.getId()).orElseThrow().getStatus());
        assertEquals(rowsBefore, rows(created.getId()).size());
        assertEquals(eventsBefore, events(created.getId()).size());
    }

    @Test
    void siFallaElOutboxNoQuedaElCambioNiLaActividadNiSeAvisaARabbitMq() {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));
        int rowsBefore = rows(created.getId()).size();
        int eventsBefore = events(created.getId()).size();
        jdbc.execute("alter table outbox_events add constraint ep331_outbox_failure check (aggregate_id <> '"
                + created.getId() + "' or event_type <> 'request.status-changed')");
        try {
            assertThrows(RuntimeException.class,
                    () -> inTransaction(() -> requests.changeStatus(created.getId(), "ADMITIDO", null, null, ANA)));

            assertEquals(RequestStatus.INGRESADO, repository.findById(created.getId()).orElseThrow().getStatus());
            assertEquals(rowsBefore, rows(created.getId()).size());
            assertEquals(eventsBefore, events(created.getId()).size());
            verify(publisher, never()).publish(eq("email.admitted"), any(), any(), any(UUID.class));
        } finally {
            jdbc.execute("alter table outbox_events drop constraint ep331_outbox_failure");
        }
    }

    @Test
    void unaTransicionRechazadaNoDejaEvento() {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));
        int eventsBefore = events(created.getId()).size();

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> inTransaction(() -> requests.changeStatus(created.getId(), "RESUELTO", null, null, ANA)));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(eventsBefore, events(created.getId()).size());
    }

    @Test
    void elRegistroExigeLaTransaccionDelCambio() {
        MunicipalRequest created = create();
        RequestActivity row = rows(created.getId()).get(0);

        assertThrows(IllegalTransactionStateException.class,
                () -> recorder.record(created, row, UUID.randomUUID()));
    }

    @Test
    void elHitoSinActorQuedaSinRol() {
        MunicipalRequest created = create();
        inTransaction(() -> assignment.take(created.getId(), ANA));

        RequestActivity milestone = rows(created.getId()).stream()
                .filter(row -> row.getKind() == ActivityKind.ASIGNACION && row.getActorId() == null)
                .findFirst().orElseThrow();
        assertNull(milestone.getActorRole());
    }

    private MunicipalRequest create() {
        return new TransactionTemplate(transactions).execute(tx ->
                requests.create(new CreateRequestDto("Hoyo", "bache", "Pasaje 1"), VECINO, null, NOW));
    }

    private void inTransaction(Runnable operation) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> operation.run());
    }

    private List<OutboxEvent> events(String requestId) {
        return outbox.findAll().stream()
                .filter(event -> event.getAggregateId().equals(requestId))
                .sorted((a, b) -> Long.compare(a.getEventSequence(), b.getEventSequence()))
                .toList();
    }

    private List<OutboxEvent> assignedEvents(String requestId) {
        return events(requestId).stream()
                .filter(event -> event.getEventType().equals(DomainEventRecorder.REQUEST_ASSIGNED))
                .toList();
    }

    private List<RequestActivity> rows(String requestId) {
        return activity.findByRequestIdOrderByOccurredAtAsc(requestId);
    }

    private static List<String> types(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getEventType).toList();
    }
}
