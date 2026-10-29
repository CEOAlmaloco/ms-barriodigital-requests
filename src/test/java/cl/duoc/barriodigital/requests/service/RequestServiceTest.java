package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestAttachment;
import cl.duoc.barriodigital.requests.domain.RequestAttachment;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestAttachmentRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    @Mock
    private MunicipalRequestRepository repository;

    @Mock
    private RequestActivityRepository activity;

    @Mock
    private RequestAttachmentRepository attachments;

    @Mock
    private EventPublisher publisher;

    @InjectMocks
    private RequestService requestService;

    // Solo inspecciona el registro y el snapshot. Las garantías de commit se prueban con JPA/H2.
    @BeforeEach
    void synchronizationForUnitTests() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket"})
    void registraSnapshotInmutableSoloParaDespuesDelCommit(RequestStatus from, String target, String type) {
        MunicipalRequest stored = new MunicipalRequest("titulo ajeno", "d", "nuevo_tipo", "direccion original",
                "vecino", "Vecino", "vecino@example.com", "Etiqueta original");
        stored.setStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(stored));
        when(repository.save(stored)).thenReturn(stored);
        RequestEventPayload expected = new RequestEventPayload(stored.getId(), "nuevo_tipo", "Etiqueta original",
                "direccion original", "vecino@example.com");

        requestService.changeStatus("id", target, null,
                new CallerContext("func", "Funcionario", "Funcionario", "func@example.com", "Otra etiqueta"));
        verifyNoInteractions(publisher);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        ReflectionTestUtils.setField(stored, "procedureLabel", "cambiada");
        ReflectionTestUtils.setField(stored, "solicitanteEmail", "otro@example.com");
        ReflectionTestUtils.setField(stored, "address", "otra direccion");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(type), eq(expected.requestId()), snapshot.capture(), trace.capture());
        assertEquals(expected, snapshot.getValue());
        if (from == RequestStatus.EN_GESTION || from == RequestStatus.EN_TERRENO) {
            verify(publisher).publish(eq(from == RequestStatus.EN_GESTION ? "email.visit" : "certificate.resolved"), eq(expected.requestId()),
                    org.mockito.ArgumentMatchers.same(snapshot.getValue()), eq(trace.getValue()));
        }
        verifyNoMoreInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_GESTION,EN_TERRENO,crew.ticket",
            "EN_TERRENO,RESUELTO,email.resolved"})
    void publicaTambienLosSnapshotsNulos(RequestStatus from, String target, String type) {
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(stored));
        when(repository.save(stored)).thenReturn(stored);
        requestService.changeStatus("id", target, null, new CallerContext("func", "Funcionario"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        verify(publisher).publish(eq(type), eq(stored.getId()),
                eq(new RequestEventPayload(stored.getId(), "bache", null, "dir", null)), trace.capture());
        if (from == RequestStatus.EN_GESTION || from == RequestStatus.EN_TERRENO) {
            verify(publisher).publish(eq(from == RequestStatus.EN_GESTION ? "email.visit" : "certificate.resolved"), eq(stored.getId()),
                    eq(new RequestEventPayload(stored.getId(), "bache", null, "dir", null)), eq(trace.getValue()));
        }
        verifyNoMoreInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_GESTION,EN_TERRENO"})
    void noPublicaInmediatamenteSinTransaccion(RequestStatus from, String target) {
        TransactionSynchronizationManager.clear();
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(stored));
        when(repository.save(stored)).thenReturn(stored);
        assertThrows(IllegalStateException.class, () -> requestService.changeStatus("id", target, null,
                new CallerContext("func", "Funcionario")));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"ADMITIDO,EN_GESTION", "INGRESADO,RECHAZADO",
            "ADMITIDO,RECHAZADO", "EN_GESTION,RECHAZADO", "EN_TERRENO,RECHAZADO"})
    void otrasTransicionesNoRegistranEventos(RequestStatus from, String target) {
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(stored));
        when(repository.save(stored)).thenReturn(stored);
        requestService.changeStatus("id", target, "motivo", new CallerContext("func", "Funcionario"));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"ADMITIDO,ADMITIDO", "EN_TERRENO,EN_TERRENO", "INGRESADO,EN_TERRENO",
            "RESUELTO,RESUELTO", "ADMITIDO,RESUELTO"})
    void transicionRechazadaNoRegistraEventos(RequestStatus from, String target) {
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(requestWithStatus(from)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> requestService.changeStatus("id", target, null, new CallerContext("func", "Funcionario")));
        assertEquals(409, ex.getStatusCode().value());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"EN_GESTION,EN_TERRENO,crew.ticket,email.visit", "EN_TERRENO,RESUELTO,email.resolved,certificate.resolved"})
    void excepcionInesperadaDelPrimerPublicadorNoSuprimeElSegundo(
            RequestStatus from, String target, String first, String second) {
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(stored));
        when(repository.save(stored)).thenReturn(stored);
        doThrow(new IllegalStateException("fallo controlado"))
                .when(publisher).publish(eq(first), any(), any(), any(UUID.class));
        requestService.changeStatus("id", target, null, new CallerContext("func", "Funcionario"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(first), eq(stored.getId()), snapshot.capture(), trace.capture());
        ArgumentCaptor<Object> emailSnapshot = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(second), eq(stored.getId()), emailSnapshot.capture(), eq(trace.getValue()));
        assertSame(snapshot.getValue(), emailSnapshot.getValue());
        verifyNoMoreInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void altaCapturaLaInstanciaDevueltaPorSaveEnUnSoloCallback(boolean nullSnapshots) {
        MunicipalRequest saved = new MunicipalRequest("guardado", "d", "tipo_guardado", "direccion guardada",
                "vecino", "Vecino", nullSnapshots ? null : "guardado@example.com",
                nullSnapshots ? null : "Etiqueta guardada");
        when(repository.save(any(MunicipalRequest.class))).thenReturn(saved);
        var expected = new RequestEventPayload(saved.getId(), "tipo_guardado", saved.getProcedureLabel(),
                "direccion guardada", saved.getSolicitanteEmail());
        MunicipalRequest returned = requestService.create(new CreateRequestDto("d", "bache", "otra direccion"),
                new CallerContext("vecino", "Vecino", "Vecino", "otro@example.com", "Otra etiqueta"));
        assertSame(saved, returned);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(publisher);
        ReflectionTestUtils.setField(saved, "procedureLabel", "Etiqueta posterior");
        ReflectionTestUtils.setField(saved, "solicitanteEmail", "posterior@example.com");
        ReflectionTestUtils.setField(saved, "address", "Direccion posterior");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(publisher).publish(eq("certificate.receipt"), eq(expected.requestId()), eq(expected), any(UUID.class));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void altaSinTransaccionNoPublicaInmediatamente() {
        TransactionSynchronizationManager.clear();
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThrows(IllegalStateException.class, () -> requestService.create(
                new CreateRequestDto("d", "bache", "dir"), new CallerContext("vecino", "Vecino")));
        verifyNoInteractions(publisher);
    }

    @Test
    void createAutogeneraTitleYGuardaSolicitante() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CallerContext vecino = new CallerContext("oid-vecino-1", "Vecino");
        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Hueco frente al 123", "bache", "Plaza Los Heroes"),
                vecino
        );

        assertEquals(RequestStatus.INGRESADO, created.getStatus());
        assertEquals("oid-vecino-1", created.getSolicitanteId());
        assertEquals("oid-vecino-1", created.getSolicitanteNombre());
        assertEquals("Plaza Los Heroes", created.getAddress());
        assertEquals("bache", created.getProcedureType());
        assertTrue(created.getTitle().contains("Bache"));
        assertTrue(created.getTitle().contains("Plaza Los Heroes"));

        ArgumentCaptor<MunicipalRequest> captor = ArgumentCaptor.forClass(MunicipalRequest.class);
        verify(repository).save(captor.capture());
        assertEquals("oid-vecino-1", captor.getValue().getSolicitanteId());
        assertNull(captor.getValue().getLatitude());
    }

    @Test
    void createGuardaSectorYPuntoDentroDeLaComuna() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto(
                        "Hueco",
                        "bache",
                        "Camino a Pitril",
                        "pitril",
                        "frente al puente",
                        -37.95,
                        -71.40,
                        "EXACTO"
                ),
                new CallerContext("oid-vecino-1", "Vecino")
        );

        assertEquals("pitril", created.getSector());
        assertEquals("frente al puente", created.getLocationReference());
        assertEquals(-37.95, created.getLatitude());
        assertEquals(-71.40, created.getLongitude());
        assertEquals(cl.duoc.barriodigital.requests.domain.LocationPrecision.EXACTO, created.getLocationPrecision());
    }

    @Test
    void createRechazaUnPuntoFueraDeLaComuna() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> requestService.create(
                new CreateRequestDto("Hueco", "bache", "Otra ciudad", null, null, -33.45, -70.66, "EXACTO"),
                new CallerContext("oid-vecino-1", "Vecino")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createAceptaUnCodigoNuevoBienFormadoYTitulaConElCodigoCrudo() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Semaforo apagado", " Semaforo ", "Calle 1"),
                new CallerContext("oid-1", "Vecino")
        );

        assertEquals("semaforo", created.getProcedureType());
        assertEquals("semaforo — Calle 1", created.getTitle());
    }

    @Test
    void createTitulaAlumbradoConLaEtiquetaConTilde() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino")
        );

        assertEquals("Alumbrado público — Calle 2", created.getTitle());
    }

    @Test
    void createGuardaElNombreDelHeaderYNoElDelBody() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "  Alex Ampuero  ")
        );

        assertEquals("Alex Ampuero", created.getSolicitanteNombre());
        assertEquals("oid-1", created.getSolicitanteId());
    }

    @Test
    void createGuardaElEmailDelHeader() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", "  vecino@example.com  ")
        );

        assertEquals("vecino@example.com", created.getSolicitanteEmail());
        assertEquals("Alex Ampuero", created.getSolicitanteNombre());
    }

    @Test
    void createSinHeaderDeEmailDejaNull() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest sinHeader = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero")
        );
        MunicipalRequest enBlanco = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", "   ")
        );

        assertNull(sinHeader.getSolicitanteEmail());
        assertNull(enBlanco.getSolicitanteEmail());
        assertEquals("Alex Ampuero", sinHeader.getSolicitanteNombre());
    }

    @Test
    void createGuardaElLabelDecodificadoDelHeaderYTitulaConEl() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Alumbrado%20p%C3%BAblico%20nocturno")
        );

        assertEquals("Alumbrado público nocturno", created.getProcedureLabel());
        assertEquals("Alumbrado público nocturno — Calle 2", created.getTitle());

        ArgumentCaptor<MunicipalRequest> captor = ArgumentCaptor.forClass(MunicipalRequest.class);
        verify(repository).save(captor.capture());
        assertEquals("Alumbrado público nocturno", captor.getValue().getProcedureLabel());
    }

    @Test
    void createDecodificaElMasComoEspacio() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Bache+grave")
        );

        assertEquals("Bache grave", created.getProcedureLabel());
        assertEquals("Bache grave — Calle 1", created.getTitle());
    }

    @Test
    void createSinHeaderDeLabelDejaNullYTitulaComoAntes() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest sinHeader = requestService.create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero")
        );
        MunicipalRequest enBlanco = requestService.create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "   ")
        );

        assertNull(sinHeader.getProcedureLabel());
        assertNull(enBlanco.getProcedureLabel());
        assertEquals("Alumbrado público — Calle 2", sinHeader.getTitle());
        assertEquals("Alumbrado público — Calle 2", enBlanco.getTitle());
    }

    @Test
    void createConHeaderDeLabelInvalidoDejaNullSinError() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest incompleto = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Bache%E0%A4%A")
        );
        MunicipalRequest noHex = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Bache%zz")
        );

        assertNull(incompleto.getProcedureLabel());
        assertNull(noHex.getProcedureLabel());
        assertEquals("Bache en calle — Calle 1", incompleto.getTitle());
        assertEquals("Bache en calle — Calle 1", noHex.getTitle());
    }

    @Test
    void snapshotDeLabelRecortaYTruncaA120() {
        assertEquals("Bache en calle", RequestService.snapshotProcedureLabel("%20%20Bache%20en%20calle%20%20"));
        assertNull(RequestService.snapshotProcedureLabel("%20%20"));

        String largo = RequestService.snapshotProcedureLabel("a".repeat(130));
        assertEquals("a".repeat(120), largo);
    }

    @Test
    void getByIdDeTramiteAntiguoDevuelveLabelNull() {
        MunicipalRequest stored = new MunicipalRequest(
                "Bache en calle — dir", "d", "bache", "dir", "oid-1", "Alex Ampuero", "vecino@example.com"
        );
        when(repository.findById("id-1")).thenReturn(Optional.of(stored));

        MunicipalRequest byId = requestService.getById("id-1", new CallerContext("oid-1", "Vecino"));

        assertNull(byId.getProcedureLabel());
        assertEquals("Bache en calle — dir", byId.getTitle());
    }

    @Test
    void getByIdDeTramiteAntiguoDevuelveEmailNullYNoLoRecalcula() {
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findById("id-1")).thenReturn(Optional.of(stored));

        MunicipalRequest byId = requestService.getById(
                "id-1",
                new CallerContext("oid-1", "Vecino", "Otro", "otro@example.com")
        );

        assertNull(byId.getSolicitanteEmail());
        assertEquals("Alex Ampuero", byId.getSolicitanteNombre());
    }

    @Test
    void changeStatusNoPisaElEmailGuardado() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest(
                "t", "d", "bache", "dir", "oid-1", "Alex Ampuero", "vecino@example.com"
        );
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(stored));

        MunicipalRequest updated = requestService.changeStatus(
                "id-1",
                "ADMITIDO",
                null,
                new CallerContext("func-1", "Funcionario", "Funcionario Nuevo", "func@example.com")
        );

        assertEquals(RequestStatus.ADMITIDO, updated.getStatus());
        assertEquals("vecino@example.com", updated.getSolicitanteEmail());
    }

    @Test
    void sinHeaderDeNombreQuedaElIdYSinNingunoQuedaSinNombre() {
        assertEquals("oid-1", RequestService.snapshotName("   ", "oid-1"));
        assertEquals("Sin nombre", RequestService.snapshotName(null, "  "));
    }

    @Test
    void getByIdYListadoDevuelvenElNombreYaGuardado() {
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findById("id-1")).thenReturn(Optional.of(stored));
        when(repository.search(isNull(), isNull(), isNull(), eq("oid-1"))).thenReturn(List.of(stored));

        MunicipalRequest byId = requestService.getById("id-1", new CallerContext("oid-1", "Vecino", "Otro"));
        List<MunicipalRequest> listed = requestService.list(null, null, null, new CallerContext("oid-1", "Vecino", "Otro"));

        assertEquals("Alex Ampuero", byId.getSolicitanteNombre());
        assertEquals("Alex Ampuero", listed.get(0).getSolicitanteNombre());
    }

    @Test
    void changeStatusNoPisaElNombreGuardado() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(stored));

        MunicipalRequest updated = requestService.changeStatus(
                "id-1",
                "ADMITIDO",
                null,
                new CallerContext("func-1", "Funcionario", "Funcionario Nuevo")
        );

        assertEquals(RequestStatus.ADMITIDO, updated.getStatus());
        assertEquals("Alex Ampuero", updated.getSolicitanteNombre());
    }

    @Test
    void createRechazaProcedureTypeConFormatoInvalido() {
        CallerContext vecino = new CallerContext("oid-1", "Vecino");

        assertBadProcedureType("semaforo roto", vecino);
        assertBadProcedureType("señal", vecino);
        assertBadProcedureType("iluminación", vecino);
        assertBadProcedureType("a".repeat(41), vecino);
        assertBadProcedureType("bache!", vecino);
        assertBadProcedureType("bache.calle", vecino);
        assertBadProcedureType("   ", vecino);
        assertBadProcedureType(null, vecino);
        verify(repository, never()).save(any());
    }

    @Test
    void createAceptaCodigoDeCuarentaCaracteres() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String code = "a".repeat(40);

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("x", code, "Calle 1"),
                new CallerContext("oid-1", "Vecino")
        );

        assertEquals(code, created.getProcedureType());
    }

    @Test
    void getByIdVecinoNoVeTramiteAjeno() {
        MunicipalRequest other = new MunicipalRequest("t", "d", "bache", "dir", "otro-oid");
        when(repository.findById("id-1")).thenReturn(Optional.of(other));

        CallerContext vecino = new CallerContext("mi-oid", "Vecino");
        assertThrows(ResponseStatusException.class, () -> requestService.getById("id-1", vecino));
    }

    @Test
    void getAttachmentVecinoNoPuedeDescargarImagenDeOtroTramite() {
        MunicipalRequest other = new MunicipalRequest("t", "d", "bache", "dir", "otro-oid");
        when(repository.findById("id-1")).thenReturn(Optional.of(other));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> requestService.getAttachment("id-1", new CallerContext("mi-oid", "Vecino"))
        );

        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(attachments);
    }

    @ParameterizedTest
    @CsvSource({"Vecino,mi-oid", "Funcionario,func-1", "Admin,admin-1", "Auditor,auditor-1"})
    void getAttachmentPermiteAlTitularYAlPersonal(String role, String userId) {
        MunicipalRequest owned = new MunicipalRequest("t", "d", "bache", "dir", "mi-oid");
        byte[] imageBytes = {1, 2, 3};
        RequestAttachment attachment = mock(RequestAttachment.class);
        when(repository.findById("id-1")).thenReturn(Optional.of(owned));
        when(attachments.findById("id-1")).thenReturn(Optional.of(attachment));
        when(attachment.getContent()).thenReturn(imageBytes);
        when(attachment.getContentType()).thenReturn("image/png");
        when(attachment.getFileName()).thenReturn("evidencia.png");

        var downloaded = requestService.getAttachment("id-1", new CallerContext(userId, role));

        assertArrayEquals(imageBytes, downloaded.content());
        assertEquals("image/png", downloaded.contentType());
        assertEquals("evidencia.png", downloaded.fileName());
    }

    @Test
    void listVecinoFuerzaFiltroPorSolicitante() {
        when(repository.search(eq(RequestStatus.INGRESADO), any(), any(), eq("mi-oid")))
                .thenReturn(List.of());

        requestService.list(
                RequestStatus.INGRESADO,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 13),
                new CallerContext("mi-oid", "Vecino")
        );

        verify(repository).search(eq(RequestStatus.INGRESADO), any(), any(), eq("mi-oid"));
    }

    @Test
    void listFuncionarioNoFiltraPorSolicitante() {
        when(repository.search(isNull(), isNull(), isNull(), isNull())).thenReturn(List.of());

        requestService.list(null, null, null, new CallerContext("func-1", "Funcionario"));

        verify(repository).search(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void getByIdLanza404SiNoExiste() {
        when(repository.findById("no-existe")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> requestService.getById("no-existe", new CallerContext("oid", "Admin")));
    }

    @Test
    void changeStatusAceptaLasOchoTransicionesValidas() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CallerContext funcionario = new CallerContext("func-1", "Funcionario");

        assertStatusChange(RequestStatus.INGRESADO, "ADMITIDO", null, RequestStatus.ADMITIDO, funcionario);
        assertStatusChange(RequestStatus.INGRESADO, "RECHAZADO", "Fuera de comuna", RequestStatus.RECHAZADO, funcionario);
        assertStatusChange(RequestStatus.ADMITIDO, "EN_GESTION", null, RequestStatus.EN_GESTION, funcionario);
        assertStatusChange(RequestStatus.ADMITIDO, "RECHAZADO", "No corresponde", RequestStatus.RECHAZADO, funcionario);
        assertStatusChange(RequestStatus.EN_GESTION, "EN_TERRENO", null, RequestStatus.EN_TERRENO, funcionario);
        assertStatusChange(RequestStatus.EN_GESTION, "RECHAZADO", "Sin cupo operativo", RequestStatus.RECHAZADO, funcionario);
        assertStatusChange(RequestStatus.EN_TERRENO, "RESUELTO", null, RequestStatus.RESUELTO, funcionario);
        assertStatusChange(RequestStatus.EN_TERRENO, "RECHAZADO", "No se encontro el domicilio", RequestStatus.RECHAZADO, funcionario);
    }

    @Test
    void changeStatusRechazaUnaInvalidaPorCadaNoTerminal() {
        CallerContext funcionario = new CallerContext("func-1", "Funcionario");
        assertConflict(RequestStatus.INGRESADO, "EN_TERRENO", funcionario);
        assertConflict(RequestStatus.ADMITIDO, "RESUELTO", funcionario);
        assertConflict(RequestStatus.EN_GESTION, "ADMITIDO", funcionario);
        assertConflict(RequestStatus.EN_TERRENO, "INGRESADO", funcionario);
    }

    @Test
    void changeStatusTerminalesNoSalen() {
        CallerContext funcionario = new CallerContext("func-1", "Funcionario");
        assertConflict(RequestStatus.RESUELTO, "RECHAZADO", funcionario);
        assertConflict(RequestStatus.RECHAZADO, "ADMITIDO", funcionario);
    }

    @Test
    void changeStatusRechazadoSinMotivoEs400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "RECHAZADO", "   ", new CallerContext("func-1", "Funcionario")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void changeStatusEnumInvalidoEs400No500() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "FOO", null, new CallerContext("func-1", "Funcionario")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void changeStatusAdminNoPuede() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "ADMITIDO", null, new CallerContext("admin-1", "Admin")));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void changeStatus404SiNoExiste() {
        when(repository.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("no-existe", "ADMITIDO", null, new CallerContext("func-1", "Funcionario")));
        assertEquals(404, ex.getStatusCode().value());
    }

    private void assertStatusChange(
            RequestStatus from,
            String rawTarget,
            String reason,
            RequestStatus expected,
            CallerContext caller
    ) {
        MunicipalRequest current = requestWithStatus(from);
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(current));

        MunicipalRequest updated = requestService.changeStatus("id-1", rawTarget, reason, caller);
        assertEquals(expected, updated.getStatus());
        if (expected == RequestStatus.RECHAZADO) {
            assertEquals(reason, updated.getRejectionReason());
        }
    }

    private void assertBadProcedureType(String procedureType, CallerContext caller) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.create(new CreateRequestDto("x", procedureType, "Calle 1"), caller));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals(
                "procedureType invalido. Debe ser minusculas, numeros, guion o guion bajo (maximo 40 caracteres)",
                ex.getReason()
        );
    }

    private void assertConflict(RequestStatus from, String rawTarget, CallerContext caller) {
        MunicipalRequest current = requestWithStatus(from);
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(current));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", rawTarget, "motivo", caller));
        assertEquals(409, ex.getStatusCode().value());
    }

    private static MunicipalRequest requestWithStatus(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "oid");
        request.setStatus(status);
        return request;
    }
}
