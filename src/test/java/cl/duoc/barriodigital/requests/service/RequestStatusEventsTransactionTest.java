package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RequestService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestStatusEventsTransactionTest {

    @Autowired RequestService service;
    @Autowired MunicipalRequestRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockBean EventPublisher publisher;

    private final CallerContext funcionario = new CallerContext("func", "Funcionario");

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket"})
    void esperaCommitExteriorYLaFilaYaEsVisibleEnOtraTransaccion(
            RequestStatus from, RequestStatus target, String type) throws Exception {
        MunicipalRequest initial = create(from);
        var reader = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                // Otro hilo y otra transacción: no puede leer la caché del EntityManager que confirma.
                assertEquals(target, reader.submit(() -> new TransactionTemplate(transactions).execute(tx ->
                        repository.findById(initial.getId()).orElseThrow().getStatus())).get(5, TimeUnit.SECONDS));
                return null;
            }).when(publisher).publish(anyString(), eq(initial.getId()), any(), any(UUID.class));
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                service.changeStatus(initial.getId(), target.name(), null, funcionario);
                verifyNoInteractions(publisher);
            });
            verifyEvents(type, initial);
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_TERRENO,RESUELTO", "EN_GESTION,EN_TERRENO"})
    void rollbackExteriorNoPublica(RequestStatus from, String target) {
        MunicipalRequest initial = create(from);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.changeStatus(initial.getId(), target, null, funcionario);
            tx.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        assertEquals(from, repository.findById(initial.getId()).orElseThrow().getStatus());
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_TERRENO,RESUELTO", "EN_GESTION,EN_TERRENO"})
    void restriccionRealDeBaseDeDatosImpideCommitYSuPublicacion(RequestStatus from, String target) {
        MunicipalRequest initial = create(from);
        jdbc.execute("alter table municipal_requests add constraint ep222_commit_failure check (id <> '"
                + initial.getId() + "' or status <> '" + target + "')");
        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                service.changeStatus(initial.getId(), target, null, funcionario);
                verifyNoInteractions(publisher);
                // El UPDATE sigue pendiente: la restricción se evalúa al hacer flush durante el commit.
            }));
            verifyNoInteractions(publisher);
            assertEquals(from, repository.findById(initial.getId()).orElseThrow().getStatus());
        } finally {
            jdbc.execute("alter table municipal_requests drop constraint ep222_commit_failure");
        }
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket"})
    void snapshotNoDependeDelObjetoQueRetornoElServicio(RequestStatus from, String target, String type) {
        MunicipalRequest initial = create(from);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            MunicipalRequest changed = service.changeStatus(initial.getId(), target, null, funcionario);
            ReflectionTestUtils.setField(changed, "procedureLabel", "Etiqueta posterior");
            ReflectionTestUtils.setField(changed, "solicitanteEmail", "posterior@example.com");
            ReflectionTestUtils.setField(changed, "address", "Direccion posterior");
        });
        verifyEvents(type, initial);
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket"})
    void falloDeEntregaPosteriorNoOcultaNiRevierteElCommit(RequestStatus from, RequestStatus target, String type) {
        MunicipalRequest initial = create(from);
        doThrow(new IllegalStateException("fallo controlado de entrega"))
                .when(publisher).publish(eq(type), anyString(), any(), any(UUID.class));
        assertDoesNotThrow(() -> service.changeStatus(initial.getId(), target.name(), null, funcionario));
        assertEquals(target, repository.findById(initial.getId()).orElseThrow().getStatus());
        verifyEvents(type, initial);
    }

    @Test
    void repeticionYSaltoInvalidoNoPublicanElPar() {
        MunicipalRequest initial = create(RequestStatus.EN_GESTION);
        service.changeStatus(initial.getId(), "EN_TERRENO", null, funcionario);
        verifyEvents("crew.ticket", initial);
        clearInvocations(publisher);
        ResponseStatusException repeated = assertThrows(ResponseStatusException.class,
                () -> service.changeStatus(initial.getId(), "EN_TERRENO", null, funcionario));
        assertEquals(409, repeated.getStatusCode().value());
        MunicipalRequest ingresado = create(RequestStatus.INGRESADO);
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> service.changeStatus(ingresado.getId(), "EN_TERRENO", null, funcionario));
        assertEquals(409, invalid.getStatusCode().value());
        verifyNoInteractions(publisher);
        assertEquals(RequestStatus.EN_TERRENO, repository.findById(initial.getId()).orElseThrow().getStatus());
        assertEquals(RequestStatus.INGRESADO, repository.findById(ingresado.getId()).orElseThrow().getStatus());
    }

    @Test
    void altaEsperaCommitExteriorYLaFilaEsVisibleEnOtraTransaccion() throws Exception {
        var reader = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                String id = invocation.getArgument(1);
                MunicipalRequest visible = reader.submit(() -> new TransactionTemplate(transactions).execute(tx ->
                        repository.findById(id).orElseThrow())).get(5, TimeUnit.SECONDS);
                assertEquals(RequestStatus.INGRESADO, visible.getStatus());
                assertEquals(payload(visible), invocation.getArgument(2));
                return null;
            }).when(publisher).publish(eq("certificate.receipt"), anyString(), any(), any(UUID.class));
            MunicipalRequest saved = new TransactionTemplate(transactions).execute(tx -> {
                MunicipalRequest created = createThroughService();
                verifyNoInteractions(publisher);
                return created;
            });
            verifyEvents("certificate.receipt", saved);
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rollbackDelAltaNoPublicaNiConservaLaFila() {
        String id = new TransactionTemplate(transactions).execute(tx -> {
            MunicipalRequest saved = createThroughService();
            verifyNoInteractions(publisher);
            tx.setRollbackOnly();
            return saved.getId();
        });
        verifyNoInteractions(publisher);
        assertTrue(repository.findById(id).isEmpty());
    }

    @Test
    void restriccionRealImpideCommitDelInsertSinPublicar() {
        jdbc.execute("alter table municipal_requests add constraint ep224_insert_failure check (description <> 'alta-invalida-ep224')");
        AtomicReference<String> id = new AtomicReference<>();
        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                MunicipalRequest saved = service.create(new CreateRequestDto("alta-invalida-ep224", "bache", "dir"), funcionario);
                id.set(saved.getId());
                verifyNoInteractions(publisher);
                // El INSERT pendiente falla durante el flush del commit, después de retornar create.
            }));
            org.junit.jupiter.api.Assertions.assertNotNull(id.get());
            verifyNoInteractions(publisher);
            assertTrue(repository.findById(id.get()).isEmpty());
        } finally {
            jdbc.execute("alter table municipal_requests drop constraint ep224_insert_failure");
        }
    }

    @Test
    void snapshotDelAltaNoDependeDeMutacionesPosteriores() {
        AtomicReference<RequestEventPayload> expected = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            MunicipalRequest saved = createThroughService();
            expected.set(payload(saved));
            ReflectionTestUtils.setField(saved, "procedureLabel", "Etiqueta posterior");
            ReflectionTestUtils.setField(saved, "solicitanteEmail", "posterior@example.com");
            ReflectionTestUtils.setField(saved, "address", "Direccion posterior");
        });
        verify(publisher).publish(eq("certificate.receipt"), eq(expected.get().requestId()), eq(expected.get()), any(UUID.class));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void falloPosteriorAlAltaNoRevierteNiOcultaElResultado() {
        doThrow(new IllegalStateException("fallo controlado de entrega"))
                .when(publisher).publish(eq("certificate.receipt"), anyString(), any(), any(UUID.class));
        MunicipalRequest saved = assertDoesNotThrow(this::createThroughService);
        assertEquals(RequestStatus.INGRESADO, repository.findById(saved.getId()).orElseThrow().getStatus());
        verifyEvents("certificate.receipt", saved);
    }

    private MunicipalRequest createThroughService() {
        return service.create(new CreateRequestDto("d", "bache", "dir"),
                new CallerContext("vecino", "Vecino", "Vecino", "vecino@example.com", "Etiqueta%20guardada"));
    }

    @Test
    void lecturasNoPublican() {
        MunicipalRequest initial = create(RequestStatus.INGRESADO);
        service.getById(initial.getId(), funcionario);
        service.list(null, null, null, funcionario);
        verifyNoInteractions(publisher);
    }

    private void verifyEvents(String type, MunicipalRequest initial) {
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        verify(publisher).publish(eq(type), eq(initial.getId()), eq(payload(initial)), trace.capture());
        if (type.equals("crew.ticket") || type.equals("email.resolved")) {
            verify(publisher).publish(eq(type.equals("crew.ticket") ? "email.visit" : "certificate.resolved"),
                    eq(initial.getId()), eq(payload(initial)), eq(trace.getValue()));
        }
        verifyNoMoreInteractions(publisher);
    }

    private MunicipalRequest create(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino", "Vecino",
                "vecino@example.com", "Etiqueta guardada");
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private RequestEventPayload payload(MunicipalRequest request) {
        return new RequestEventPayload(request.getId(), request.getProcedureType(), request.getProcedureLabel(),
                request.getAddress(), request.getSolicitanteEmail());
    }
}
