package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.infrastructure.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestActivityAttachment;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.adapter.out.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.adapter.out.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.OutboxEventRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.RequestActivityAttachmentRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.adapter.in.web.CallerContext;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.ActividadView.EventoView;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.CreateRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Comentario del titular contra JPA real en H2 (contrato EP3-64): marca, foto y evento en una transacción,
 * tope bajo el bloqueo y descarga atada al trámite. La FK compuesta la crea db/h2-citizen-comment.sql, como en Oracle.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestService.class, RequestActivityService.class, FolioCounterService.class, DomainEventRecorder.class,
        ClockConfig.class, NotificationPreferenceService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CitizenCommentH2Test {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
    private static final CallerContext VECINO =
            new CallerContext("oid-vecino", "Vecino", "Vecino%20Test", "vecino@example.com", "Bache", "5");
    private static final CallerContext OTRO_VECINO =
            new CallerContext("oid-otro", "Vecino", "Otro", "otro@example.com", "Bache", "5");
    private static final CallerContext FUNCIONARIO = new CallerContext("oid-ana", "Funcionario", "Ana");
    private static final CallerContext AUDITOR = new CallerContext("oid-aud", "Auditor", "Auditor");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final ImageAttachmentSanitizer.SanitizedImage IMAGE =
            new ImageAttachmentSanitizer.SanitizedImage(PNG, "image/png", "evidencia.png");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired RequestService requests;
    @Autowired RequestActivityService activityService;
    @Autowired FolioCounterService folios;
    @Autowired MunicipalRequestRepository repository;
    @Autowired RequestActivityRepository activity;
    @Autowired RequestActivityAttachmentRepository commentAttachments;
    @Autowired OutboxEventRepository outbox;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockBean EventPublisher publisher;

    @BeforeEach
    void clean() {
        commentAttachments.deleteAll();
        outbox.deleteAll();
        activity.deleteAll();
        repository.deleteAll();
        folios.ensureYear(Folio.yearOf(NOW));
    }

    @Test
    void dejaLaMarcaLaFotoYElEventoSinElTextoNiComandosDeRabbitMq() throws Exception {
        MunicipalRequest created = create(VECINO);

        ActividadView view = activityService.comment(created.getId(), "  Ahora se acumula agua  ", IMAGE, VECINO);

        RequestActivity row = commentRow(created.getId());
        assertEquals(ActivityVisibility.PUBLICO, row.getVisibility());
        assertEquals("Ahora se acumula agua", row.getNote());
        assertEquals("Agregó un comentario con una foto", row.getSummary());
        assertEquals("Vecino", row.getActorRole());
        assertTrue(row.isHasAttachment());
        RequestActivityAttachment attachment = commentAttachments.findById(row.getId()).orElseThrow();
        assertEquals(created.getId(), attachment.getRequestId());
        assertArrayEquals(PNG, attachment.getContent());

        OutboxEvent event = lastEvent(created.getId());
        assertEquals(DomainEventRecorder.REQUEST_CITIZEN_COMMENT, event.getEventType());
        JsonNode json = JSON.readTree(event.getPayload());
        assertEquals(row.getOccurredAt().toString(), json.get("occurredAt").asText());
        assertEquals("Vecino", json.get("actor").get("role").asText());
        JsonNode payload = json.get("payload");
        assertEquals(List.of("requestId", "folio", "hasAttachment"), fieldNames(payload));
        assertEquals(created.getFolio(), payload.get("folio").asText());
        assertTrue(payload.get("hasAttachment").booleanValue());
        assertFalse(event.getPayload().contains("acumula agua"));

        EventoView evento = view.eventos().get(view.eventos().size() - 1);
        assertEquals(ActivityKind.COMENTARIO, evento.kind());
        assertTrue(evento.hasAttachment());
        verify(publisher).publish(eq("certificate.receipt"), eq(created.getId()), any(), any());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void sinFotoNoGuardaImagenYElEventoLoIndica() throws Exception {
        MunicipalRequest created = create(VECINO);

        activityService.comment(created.getId(), "Sigue igual", null, VECINO);

        RequestActivity row = commentRow(created.getId());
        assertFalse(row.isHasAttachment());
        assertEquals("Agregó un comentario", row.getSummary());
        assertEquals(0, commentAttachments.count());
        JsonNode payload = JSON.readTree(lastEvent(created.getId()).getPayload()).get("payload");
        assertFalse(payload.get("hasAttachment").booleanValue());
    }

    @Test
    void unRollbackNoDejaNiLaMarcaNiLaFotoNiElEvento() {
        MunicipalRequest created = create(VECINO);
        long eventsBefore = outbox.count();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            activityService.comment(created.getId(), "Con foto", IMAGE, VECINO);
            tx.setRollbackOnly();
        });

        assertEquals(0, activity.countByRequestIdAndKind(created.getId(), ActivityKind.COMENTARIO));
        assertEquals(0, commentAttachments.count());
        assertEquals(eventsBefore, outbox.count());
    }

    @Test
    void elComentarioYElCambioDeEstadoPosteriorTienenSequenceCrecientes() {
        MunicipalRequest created = create(VECINO);
        activityService.comment(created.getId(), "Antecedente", null, VECINO);
        MunicipalRequest found = repository.findById(created.getId()).orElseThrow();
        found.assignTo("oid-ana", "Ana");
        repository.saveAndFlush(found);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                requests.changeStatus(created.getId(), "ADMITIDO", null, null, FUNCIONARIO));

        List<OutboxEvent> events = events(created.getId());
        assertEquals(List.of(DomainEventRecorder.REQUEST_CREATED, DomainEventRecorder.REQUEST_CITIZEN_COMMENT,
                DomainEventRecorder.REQUEST_STATUS_CHANGED), events.stream().map(OutboxEvent::getEventType).toList());
        assertTrue(events.get(0).getEventSequence() < events.get(1).getEventSequence());
        assertTrue(events.get(1).getEventSequence() < events.get(2).getEventSequence());
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"RESUELTO", "RECHAZADO", "CANCELADO"})
    void unTramiteCerradoResponde409SinDejarNada(RequestStatus closed) {
        MunicipalRequest created = create(VECINO);
        MunicipalRequest found = repository.findById(created.getId()).orElseThrow();
        found.setStatus(closed);
        repository.saveAndFlush(found);
        long eventsBefore = outbox.count();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> activityService.comment(created.getId(), "Tarde", IMAGE, VECINO));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("No se puede comentar un tramite cerrado", error.getReason());
        assertEquals(0, activity.countByRequestIdAndKind(created.getId(), ActivityKind.COMENTARIO));
        assertEquals(0, commentAttachments.count());
        assertEquals(eventsBefore, outbox.count());
    }

    @Test
    void elComentario21Responde409YNoDejaNada() {
        MunicipalRequest created = create(VECINO);
        for (int i = 1; i <= RequestActivityService.MAX_COMMENTS; i++) {
            activityService.comment(created.getId(), "Comentario " + i, null, VECINO);
        }
        long eventsBefore = outbox.count();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> activityService.comment(created.getId(), "Uno mas", IMAGE, VECINO));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("El tramite ya tiene el maximo de 20 comentarios", error.getReason());
        assertEquals(20, activity.countByRequestIdAndKind(created.getId(), ActivityKind.COMENTARIO));
        assertEquals(0, commentAttachments.count());
        assertEquals(eventsBefore, outbox.count());
    }

    @Test
    void conDiecinueveComentariosDosSimultaneosSeSerializanYSoloUnoPasa() throws Exception {
        MunicipalRequest created = create(VECINO);
        for (int i = 1; i < RequestActivityService.MAX_COMMENTS; i++) {
            activityService.comment(created.getId(), "Comentario " + i, null, VECINO);
        }
        String id = created.getId();
        CountDownLatch commented = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                activityService.comment(id, "Vigésimo", null, VECINO);
                commented.countDown();
                await(commit);
                return 201;
            }));
            assertTrue(commented.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                try {
                    activityService.comment(id, "Vigésimo primero", null, VECINO);
                    return 201;
                } catch (ResponseStatusException ex) {
                    return ex.getStatusCode().value();
                }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            // La primera transacción sigue abierta con la fila bloqueada: la segunda espera para contar.
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            commit.countDown();
            assertEquals(201, first.get(5, TimeUnit.SECONDS));
            assertEquals(409, second.get(5, TimeUnit.SECONDS));
            assertEquals(20, activity.countByRequestIdAndKind(id, ActivityKind.COMENTARIO));
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void laDescargaEntregaLaFotoAlTitularYAlPersonal() {
        MunicipalRequest created = create(VECINO);
        activityService.comment(created.getId(), "Con foto", IMAGE, VECINO);
        String commentId = commentRow(created.getId()).getId();

        for (CallerContext caller : List.of(VECINO, FUNCIONARIO, AUDITOR)) {
            RequestAttachmentContent content = activityService.getCommentAttachment(created.getId(), commentId, caller);
            assertArrayEquals(PNG, content.content());
            assertEquals("image/png", content.contentType());
        }
    }

    @Test
    void laDescargaExigeQueElComentarioSeaDelTramiteYTengaFoto() {
        MunicipalRequest first = create(VECINO);
        MunicipalRequest second = create(VECINO);
        activityService.comment(first.getId(), "Foto del primero", IMAGE, VECINO);
        activityService.comment(second.getId(), "Sin foto", null, VECINO);
        String firstComment = commentRow(first.getId()).getId();
        String secondComment = commentRow(second.getId()).getId();
        String created = activity.findByRequestIdOrderByActivitySequenceAsc(first.getId()).get(0).getId();

        // El titular y el personal ven los dos trámites: igual el comentario de uno no se descarga por el otro.
        for (CallerContext caller : List.of(VECINO, FUNCIONARIO)) {
            assertImageNotFound(() -> activityService.getCommentAttachment(second.getId(), firstComment, caller));
        }
        assertImageNotFound(() -> activityService.getCommentAttachment(second.getId(), secondComment, VECINO));
        assertImageNotFound(() -> activityService.getCommentAttachment(first.getId(), created, VECINO));
        assertImageNotFound(() -> activityService.getCommentAttachment(first.getId(), "no-existe", VECINO));

        ResponseStatusException other = assertThrows(ResponseStatusException.class,
                () -> activityService.getCommentAttachment(first.getId(), firstComment, OTRO_VECINO));
        assertEquals(404, other.getStatusCode().value());
        assertEquals("Tramite no encontrado", other.getReason());
    }

    @Test
    void laFkCompuestaImpideUnaFotoConOtroTramite() {
        MunicipalRequest first = create(VECINO);
        MunicipalRequest second = create(VECINO);
        activityService.comment(first.getId(), "Sin foto", null, VECINO);
        String comment = commentRow(first.getId()).getId();

        String insert = "INSERT INTO request_activity_attachments"
                + " (activity_id, request_id, attachment_data, content_type, file_name, created_at)"
                + " VALUES (?, ?, ?, 'image/png', 'evidencia.png', CURRENT_TIMESTAMP)";

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, comment, second.getId(), PNG));
        assertEquals(0, commentAttachments.count());
        // Control: con el trámite del comentario, la misma fila entra. El rechazo anterior es la FK.
        assertEquals(1, jdbc.update(insert, comment, first.getId(), PNG));
    }

    @Test
    void elHistorialMuestraElComentarioSinCambiarLosTiempos() {
        MunicipalRequest created = create(VECINO);
        activityService.comment(created.getId(), "Antecedente", IMAGE, VECINO);

        ActividadView own = activityService.get(created.getId(), VECINO);
        EventoView ownComment = own.eventos().get(own.eventos().size() - 1);
        assertEquals("Vecino Test", ownComment.actorName());
        assertEquals("oid-vecino", ownComment.actorId());
        assertNull(ownComment.actorRole());
        assertEquals("Antecedente", ownComment.note());
        assertTrue(ownComment.hasAttachment());
        assertNull(own.tiempos().primeraAtencionSegundos());
        assertFalse(own.eventos().get(0).hasAttachment());

        ActividadView staff = activityService.get(created.getId(), FUNCIONARIO);
        EventoView staffComment = staff.eventos().get(staff.eventos().size() - 1);
        assertEquals("Vecino", staffComment.actorRole());
        assertNull(staff.tiempos().primeraAtencionSegundos());
    }

    private MunicipalRequest create(CallerContext caller) {
        return new TransactionTemplate(transactions).execute(tx ->
                requests.create(new CreateRequestDto("Hoyo", "bache", "Pasaje 1"), caller, null, NOW));
    }

    private RequestActivity commentRow(String requestId) {
        List<RequestActivity> comments = activity.findByRequestIdOrderByActivitySequenceAsc(requestId).stream()
                .filter(row -> row.getKind() == ActivityKind.COMENTARIO)
                .toList();
        assertEquals(1, comments.size());
        return comments.get(0);
    }

    private List<OutboxEvent> events(String requestId) {
        return outbox.findAll().stream()
                .filter(event -> event.getAggregateId().equals(requestId))
                .sorted((a, b) -> Long.compare(a.getEventSequence(), b.getEventSequence()))
                .toList();
    }

    private OutboxEvent lastEvent(String requestId) {
        List<OutboxEvent> events = events(requestId);
        return events.get(events.size() - 1);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertImageNotFound(Executable call) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, call);
        assertEquals(404, error.getStatusCode().value());
        assertEquals("Imagen no encontrada", error.getReason());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La prueba no liberó la transacción");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
