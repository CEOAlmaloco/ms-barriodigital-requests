package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved"})
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
            }).when(publisher).publish(eq(type), eq(initial.getId()), any());
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                service.changeStatus(initial.getId(), target.name(), null, funcionario);
                verifyNoInteractions(publisher);
            });
            verify(publisher).publish(type, initial.getId(), payload(initial));
            verifyNoMoreInteractions(publisher);
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_TERRENO,RESUELTO"})
    void rollbackExteriorNoPublica(RequestStatus from, String target) {
        MunicipalRequest initial = create(from);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.changeStatus(initial.getId(), target, null, funcionario);
            tx.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        assertEquals(from, repository.findById(initial.getId()).orElseThrow().getStatus());
    }

    @Test
    void restriccionRealDeBaseDeDatosImpideCommitYSuPublicacion() {
        MunicipalRequest initial = create(RequestStatus.INGRESADO);
        jdbc.execute("alter table municipal_requests add constraint ep222_commit_failure check (id <> '"
                + initial.getId() + "' or status <> 'ADMITIDO')");
        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                service.changeStatus(initial.getId(), "ADMITIDO", null, funcionario);
                verifyNoInteractions(publisher);
                // El UPDATE sigue pendiente: la restricción se evalúa al hacer flush durante el commit.
            }));
            verifyNoInteractions(publisher);
            assertEquals(RequestStatus.INGRESADO, repository.findById(initial.getId()).orElseThrow().getStatus());
        } finally {
            jdbc.execute("alter table municipal_requests drop constraint ep222_commit_failure");
        }
    }

    @Test
    void snapshotNoDependeDelObjetoQueRetornoElServicio() {
        MunicipalRequest initial = create(RequestStatus.INGRESADO);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            MunicipalRequest changed = service.changeStatus(initial.getId(), "ADMITIDO", null, funcionario);
            ReflectionTestUtils.setField(changed, "procedureLabel", "Etiqueta posterior");
            ReflectionTestUtils.setField(changed, "solicitanteEmail", "posterior@example.com");
            ReflectionTestUtils.setField(changed, "address", "Direccion posterior");
        });
        verify(publisher).publish("email.admitted", initial.getId(), payload(initial));
    }

    @Test
    void falloDeEntregaPosteriorNoOcultaNiRevierteElCommit() {
        MunicipalRequest initial = create(RequestStatus.INGRESADO);
        doThrow(new IllegalStateException("fallo controlado de entrega"))
                .when(publisher).publish(anyString(), anyString(), any());
        assertDoesNotThrow(() -> service.changeStatus(initial.getId(), "ADMITIDO", null, funcionario));
        assertEquals(RequestStatus.ADMITIDO, repository.findById(initial.getId()).orElseThrow().getStatus());
    }

    @Test
    void lecturasYAltaNoPublican() {
        MunicipalRequest initial = create(RequestStatus.INGRESADO);
        service.getById(initial.getId(), funcionario);
        service.list(null, null, null, funcionario);
        service.create(new cl.duoc.barriodigital.requests.web.dto.CreateRequestDto("d", "bache", "dir"), funcionario);
        verifyNoInteractions(publisher);
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
