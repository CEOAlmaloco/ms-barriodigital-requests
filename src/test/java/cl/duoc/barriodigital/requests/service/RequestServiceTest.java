package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.TestListQueries;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
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
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Mock
    private FolioCounterService folios;

    @InjectMocks
    private RequestService requestService;

    // Solo inspecciona el registro y el snapshot. Las garantías de commit se prueban con JPA/H2.
    /** Instante del alta que pasaría el orquestador. */
    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");

    @BeforeEach
    void synchronizationForUnitTests() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(folios.next(anyInt())).thenReturn("BD-2026-000001");
    }

    /** Alta sin imagen con el instante fijo, como la llama el orquestador. */
    private MunicipalRequest create(CreateRequestDto body, CallerContext caller) {
        return requestService.create(body, caller, null, NOW);
    }

    /** Alta con imagen con el instante fijo, como la llama el orquestador. */
    private MunicipalRequest create(CreateRequestDto body, CallerContext caller, ImageAttachmentSanitizer.SanitizedImage image) {
        return requestService.create(body, caller, image, NOW);
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
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(stored)));
        when(repository.save(stored)).thenReturn(stored);
        RequestEventPayload expected = new RequestEventPayload(stored.getId(), stored.getFolio(), "nuevo_tipo", "Etiqueta original",
                "direccion original", "vecino@example.com");

        requestService.changeStatus("id", target, null,
                null,
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
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(stored)));
        when(repository.save(stored)).thenReturn(stored);
        requestService.changeStatus("id", target, null, null, new CallerContext("func", "Funcionario"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
        verify(publisher).publish(eq(type), eq(stored.getId()),
                eq(new RequestEventPayload(stored.getId(), stored.getFolio(), "bache", null, "dir", null)), trace.capture());
        if (from == RequestStatus.EN_GESTION || from == RequestStatus.EN_TERRENO) {
            verify(publisher).publish(eq(from == RequestStatus.EN_GESTION ? "email.visit" : "certificate.resolved"), eq(stored.getId()),
                    eq(new RequestEventPayload(stored.getId(), stored.getFolio(), "bache", null, "dir", null)), eq(trace.getValue()));
        }
        verifyNoMoreInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_GESTION,EN_TERRENO"})
    void noPublicaInmediatamenteSinTransaccion(RequestStatus from, String target) {
        TransactionSynchronizationManager.clear();
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(stored)));
        when(repository.save(stored)).thenReturn(stored);
        assertThrows(IllegalStateException.class, () -> requestService.changeStatus("id", target, null,
                null,
                new CallerContext("func", "Funcionario")));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"ADMITIDO,EN_GESTION", "INGRESADO,RECHAZADO",
            "ADMITIDO,RECHAZADO", "EN_GESTION,RECHAZADO", "EN_TERRENO,RECHAZADO"})
    void otrasTransicionesNoRegistranEventos(RequestStatus from, String target) {
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(stored)));
        when(repository.save(stored)).thenReturn(stored);
        requestService.changeStatus("id", target, "motivo", null, new CallerContext("func", "Funcionario"));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"ADMITIDO,ADMITIDO", "EN_TERRENO,EN_TERRENO", "INGRESADO,EN_TERRENO",
            "RESUELTO,RESUELTO", "ADMITIDO,RESUELTO"})
    void transicionRechazadaNoRegistraEventos(RequestStatus from, String target) {
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(requestWithStatus(from))));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> requestService.changeStatus("id", target, null, null, new CallerContext("func", "Funcionario")));
        assertEquals(409, ex.getStatusCode().value());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"EN_GESTION,EN_TERRENO,crew.ticket,email.visit", "EN_TERRENO,RESUELTO,email.resolved,certificate.resolved"})
    void excepcionInesperadaDelPrimerPublicadorNoSuprimeElSegundo(
            RequestStatus from, String target, String first, String second) {
        MunicipalRequest stored = requestWithStatus(from);
        when(repository.findByIdForUpdate("id")).thenReturn(Optional.of(responsible(stored)));
        when(repository.save(stored)).thenReturn(stored);
        doThrow(new IllegalStateException("fallo controlado"))
                .when(publisher).publish(eq(first), any(), any(), any(UUID.class));
        requestService.changeStatus("id", target, null, null, new CallerContext("func", "Funcionario"));
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
        var expected = new RequestEventPayload(saved.getId(), saved.getFolio(), "tipo_guardado", saved.getProcedureLabel(),
                "direccion guardada", saved.getSolicitanteEmail());
        MunicipalRequest returned = create(new CreateRequestDto("d", "bache", "otra direccion"),
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
        assertThrows(IllegalStateException.class, () -> create(
                new CreateRequestDto("d", "bache", "dir"), new CallerContext("vecino", "Vecino")));
        verifyNoInteractions(publisher);
    }

    @Test
    void createAutogeneraTitleYGuardaSolicitante() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CallerContext vecino = new CallerContext("oid-vecino-1", "Vecino");
        MunicipalRequest created = create(
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

        MunicipalRequest created = create(
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
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> create(
                new CreateRequestDto("Hueco", "bache", "Otra ciudad", null, null, -33.45, -70.66, "EXACTO"),
                new CallerContext("oid-vecino-1", "Vecino")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createAceptaUnCodigoNuevoBienFormadoYTitulaConElCodigoCrudo() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = create(
                new CreateRequestDto("Semaforo apagado", " Semaforo ", "Calle 1"),
                new CallerContext("oid-1", "Vecino")
        );

        assertEquals("semaforo", created.getProcedureType());
        assertEquals("semaforo — Calle 1", created.getTitle());
    }

    @Test
    void createTitulaAlumbradoConLaEtiquetaConTilde() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino")
        );

        assertEquals("Alumbrado público — Calle 2", created.getTitle());
    }

    @Test
    void createGuardaElNombreDelHeaderYNoElDelBody() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "  Alex Ampuero  ")
        );

        assertEquals("Alex Ampuero", created.getSolicitanteNombre());
        assertEquals("oid-1", created.getSolicitanteId());
    }

    @Test
    void createGuardaElEmailDelHeader() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", "  vecino@example.com  ")
        );

        assertEquals("vecino@example.com", created.getSolicitanteEmail());
        assertEquals("Alex Ampuero", created.getSolicitanteNombre());
    }

    @Test
    void createSinHeaderDeEmailDejaNull() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest sinHeader = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero")
        );
        MunicipalRequest enBlanco = create(
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

        MunicipalRequest created = create(
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

        MunicipalRequest created = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Bache+grave")
        );

        assertEquals("Bache grave", created.getProcedureLabel());
        assertEquals("Bache grave — Calle 1", created.getTitle());
    }

    @Test
    void createSinHeaderDeLabelDejaNullYTitulaComoAntes() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest sinHeader = create(
                new CreateRequestDto("Poste sin luz", "alumbrado", "Calle 2"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero")
        );
        MunicipalRequest enBlanco = create(
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

        MunicipalRequest incompleto = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Alex Ampuero", null, "Bache%E0%A4%A")
        );
        MunicipalRequest noHex = create(
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
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(stored)));

        MunicipalRequest updated = requestService.changeStatus(
                "id-1",
                "ADMITIDO",
                null,
                null,
                new CallerContext("func", "Funcionario", "Funcionario Nuevo", "func@example.com")
        );

        assertEquals(RequestStatus.ADMITIDO, updated.getStatus());
        assertEquals("vecino@example.com", updated.getSolicitanteEmail());
    }

    @Test
    void createAsignaElFolioDelAnioDeChileConElInstanteRecibido() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant newYearEve = Instant.parse("2027-01-01T02:30:00Z");
        when(folios.next(2026)).thenReturn("BD-2026-000042");

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"), new CallerContext("oid-1", "Vecino"), null, newYearEve);

        ArgumentCaptor<RequestActivity> mark = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(mark.capture());
        assertEquals("BD-2026-000042", created.getFolio());
        assertEquals(newYearEve, created.getCreatedAt());
        assertEquals(newYearEve, mark.getValue().getOccurredAt());
    }

    @Test
    void unAltaInvalidaNoTomaNumeroDeFolio() {
        assertThrows(ResponseStatusException.class, () -> create(
                new CreateRequestDto("x", "Tipo Invalido", "Calle 1"), new CallerContext("oid-1", "Vecino")));
        assertThrows(ResponseStatusException.class, () -> create(
                new CreateRequestDto("Hueco", "bache", "Otra ciudad", null, null, -33.45, -70.66, "EXACTO"),
                new CallerContext("oid-1", "Vecino")));

        verify(folios, never()).next(anyInt());
        verify(repository, never()).save(any());
    }

    @Test
    void sinHeaderDeNombreQuedaElIdYSinNingunoQuedaSinNombre() {
        assertEquals("oid-1", RequestService.snapshotName("   ", "oid-1"));
        assertEquals("Sin nombre", RequestService.snapshotName(null, "  "));
    }

    @Test
    void createGuardaElNombreDecodificadoEnElTramiteYEnElHistorial() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = create(
                new CreateRequestDto("Hueco", "bache", "Calle 1"),
                new CallerContext("oid-1", "Vecino", "Ana%20Mar%C3%ADa%20Mu%C3%B1oz")
        );

        ArgumentCaptor<RequestActivity> event = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(event.capture());
        assertEquals("Ana María Muñoz", created.getSolicitanteNombre());
        assertEquals("Ana María Muñoz", event.getValue().getActorName());
    }

    @Test
    void changeStatusGuardaElNombreDecodificadoDelFuncionarioEnElHistorial() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(stored)));

        requestService.changeStatus(
                "id-1",
                "ADMITIDO",
                null,
                null,
                new CallerContext("func", "Funcionario", "Jos%C3%A9%20Pe%C3%B1a")
        );

        ArgumentCaptor<RequestActivity> event = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(event.capture());
        assertEquals("José Peña", event.getValue().getActorName());
        assertEquals("Alex Ampuero", stored.getSolicitanteNombre());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Juan%2BPedro|Juan+Pedro",
            "Ana María|Ana María",
            "%20%20Ana%20%20|Ana",
            "Ana%E|oid-1",
            "Ana%0AMar%C3%ADa|oid-1",
            "Ana%09Soto|oid-1",
            "Ana%0D%0AX-User-Roles:%20Admin|oid-1"
    })
    void snapshotNameDecodificaYUsaElIdSiElValorNoEsValido(String header, String expected) {
        assertEquals(expected, RequestService.snapshotName(header, "oid-1"));
    }

    @Test
    void snapshotNameRecortaA200SinPartirUnEmoji() {
        assertEquals(200, RequestService.snapshotName("a".repeat(250), "oid-1").length());

        String encoded = URLEncoder.encode("a".repeat(199) + "😀" + "b", StandardCharsets.UTF_8);
        assertEquals("a".repeat(199), RequestService.snapshotName(encoded, "oid-1"));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-06, 2026-11-06T03:00:00Z, 2026-11-07T03:00:00Z",
            "2026-09-06, 2026-09-06T04:00:00Z, 2026-09-07T03:00:00Z",
            "2026-04-04, 2026-04-04T03:00:00Z, 2026-04-05T04:00:00Z"
    })
    void unDiaVaDeSuMedianocheALaSiguienteEnHoraDeChileInclusoConCambioDeHora(LocalDate day, String from, String before) {
        assertEquals(Instant.parse(from), RequestService.startOfCivilDay(day));
        assertEquals(Instant.parse(before), RequestService.startOfCivilDay(day.plusDays(1)));
    }

    @Test
    void getByIdYListadoDevuelvenElNombreYaGuardado() {
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findById("id-1")).thenReturn(Optional.of(stored));
        when(repository.findAll(ArgumentMatchers.<Specification<MunicipalRequest>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stored)));

        MunicipalRequest byId = requestService.getById("id-1", new CallerContext("oid-1", "Vecino", "Otro"));
        List<MunicipalRequest> listed = requestService.list(TestListQueries.all(), new CallerContext("oid-1", "Vecino", "Otro"))
                .getContent();

        assertEquals("Alex Ampuero", byId.getSolicitanteNombre());
        assertEquals("Alex Ampuero", listed.get(0).getSolicitanteNombre());
    }

    @Test
    void changeStatusNoPisaElNombreGuardado() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(stored)));

        MunicipalRequest updated = requestService.changeStatus(
                "id-1",
                "ADMITIDO",
                null,
                null,
                new CallerContext("func", "Funcionario", "Funcionario Nuevo")
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

        MunicipalRequest created = create(
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
    void getByIdLanza404SiNoExiste() {
        when(repository.findById("no-existe")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> requestService.getById("no-existe", new CallerContext("oid", "Admin")));
    }

    @Test
    void changeStatusAceptaLasOchoTransicionesValidas() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CallerContext funcionario = new CallerContext("func", "Funcionario");

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
        CallerContext funcionario = new CallerContext("func", "Funcionario");
        assertConflict(RequestStatus.INGRESADO, "EN_TERRENO", funcionario);
        assertConflict(RequestStatus.ADMITIDO, "RESUELTO", funcionario);
        assertConflict(RequestStatus.EN_GESTION, "ADMITIDO", funcionario);
        assertConflict(RequestStatus.EN_TERRENO, "INGRESADO", funcionario);
    }

    @Test
    void changeStatusTerminalesNoSalen() {
        CallerContext funcionario = new CallerContext("func", "Funcionario");
        assertConflict(RequestStatus.RESUELTO, "RECHAZADO", funcionario);
        assertConflict(RequestStatus.RECHAZADO, "ADMITIDO", funcionario);
    }

    @Test
    void changeStatusRechazadoSinMotivoEs400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "RECHAZADO", "   ", null, new CallerContext("func", "Funcionario")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void changeStatusEnumInvalidoEs400No500() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "FOO", null, null, new CallerContext("func", "Funcionario")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void changeStatusDelAdminSinMotivoResponde400AntesDeBuscarElTramite(String reason) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> requestService.changeStatus(
                "id-1", "ADMITIDO", null, reason.isEmpty() ? null : reason, new CallerContext("admin-1", "Admin")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("interventionReason es obligatorio para el Admin", ex.getReason());
        verify(repository, never()).findByIdForUpdate(any());
    }

    @Test
    void changeStatusDelAdminConMotivoGuardaLaIntervencionFueraDelSummary() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(stored)));

        requestService.changeStatus("id-1", "RECHAZADO", "Duplicado", "  Reclamo en la OIRS  ",
                new CallerContext("admin-1", "Admin", "Admin%20Uno"));

        ArgumentCaptor<RequestActivity> mark = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(mark.capture());
        assertEquals("Reclamo en la OIRS", mark.getValue().getInterventionReason());
        assertEquals("Duplicado", mark.getValue().getReason());
        assertEquals(false, mark.getValue().getSummary().contains("OIRS"));
        assertEquals("Admin Uno", mark.getValue().getActorName());
    }

    @Test
    void changeStatusDelAdminConMotivoDeMasDe500Responde400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> requestService.changeStatus(
                "id-1", "ADMITIDO", null, "x".repeat(501), new CallerContext("admin-1", "Admin")));

        assertEquals("interventionReason no puede superar 500 caracteres", ex.getReason());
    }

    @Test
    void changeStatusDelFuncionarioIgnoraElMotivoDeIntervencion() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex Ampuero");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(stored)));

        requestService.changeStatus("id-1", "ADMITIDO", null, "no aplica", new CallerContext("func", "Funcionario"));

        ArgumentCaptor<RequestActivity> mark = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(mark.capture());
        assertNull(mark.getValue().getInterventionReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Auditor"})
    void changeStatusDelVecinoODelAuditorResponde403ConElTextoQueComparaElBff(String role) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "ADMITIDO", null, null, new CallerContext("u-1", role)));

        assertEquals(403, ex.getStatusCode().value());
        assertEquals("Solo un Funcionario puede cambiar el estado", ex.getReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"otro-func", ""})
    void elFuncionarioQueNoEsElResponsableNoCambiaElEstado(String assignee) {
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1");
        if (!assignee.isEmpty()) {
            stored.assignTo(assignee, "Otro");
        }
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(stored));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "ADMITIDO", null, null, new CallerContext("func", "Funcionario")));

        assertEquals(403, ex.getStatusCode().value());
        assertEquals("Solo el responsable del tramite puede operarlo", ex.getReason());
        assertEquals(RequestStatus.INGRESADO, stored.getStatus());
        verify(activity, never()).save(any());
    }

    @Test
    void elResponsableNoLeGanaAlConflictoDeTransicionPeroSiAlNoResponsable() {
        // Orden de EP3-04, sección 5: el 403 del no responsable va antes del 409 de la transición.
        MunicipalRequest stored = requestWithStatus(RequestStatus.RESUELTO);
        stored.assignTo("otro-func", "Otro");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(stored));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", "ADMITIDO", null, null, new CallerContext("func", "Funcionario")));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void elAdminOperaUnTramiteSinResponsableComoIntervencion() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MunicipalRequest stored = new MunicipalRequest("t", "d", "bache", "dir", "oid-1");
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(stored));

        MunicipalRequest updated = requestService.changeStatus("id-1", "ADMITIDO", null, "Atraso de la unidad",
                new CallerContext("adm-1", "Admin"));

        assertEquals(RequestStatus.ADMITIDO, updated.getStatus());
    }

    @Test
    void elFiltroAssigneeEsSoloParaStaffYSoloAceptaMeONone() {
        ResponseStatusException vecino = assertThrows(ResponseStatusException.class, () ->
                requestService.list(query().assignee("me").build(), new CallerContext("vecino-1", "Vecino")));
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class, () ->
                requestService.list(query().assignee("oid-ana").build(), new CallerContext("func", "Funcionario")));

        assertEquals(403, vecino.getStatusCode().value());
        assertEquals("El filtro assignee es solo para staff", vecino.getReason());
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("assignee debe ser me o none", invalid.getReason());
        verifyNoInteractions(repository);
    }

    @Test
    void elListadoRechazaParametrosInvalidosConElMensajeDelContrato() {
        CallerContext funcionario = new CallerContext("func", "Funcionario");
        LocalDate day = LocalDate.of(2026, 11, 6);

        assertListRejected(query().page(-1), funcionario, "page debe ser 0 o mayor");
        assertListRejected(query().size(0), funcionario, "size debe estar entre 1 y 50");
        assertListRejected(query().size(51), funcionario, "size debe estar entre 1 y 50");
        assertListRejected(query().open("si"), funcionario, "open debe ser true o false");
        assertListRejected(query().open("TRUE"), funcionario, "open debe ser true o false");
        assertListRejected(query().q("a".repeat(101)), funcionario, "q no puede superar 100 caracteres");
        assertListRejected(query().sector("Ralco"), funcionario, "sector no es valido");
        assertListRejected(query().from(day.plusDays(1)).to(day), funcionario, "from no puede ser posterior a to");
        for (String sort : List.of("title", "createdAt,up", "createdAt,", "status,asc,folio", ",asc", "solicitanteEmail")) {
            assertListRejected(query().sort(sort), funcionario, "sort no es valido");
        }
        verifyNoInteractions(repository);
    }

    @Test
    void elListadoAceptaLosBordesYPideLaPaginaSinOrden() {
        when(repository.findAll(ArgumentMatchers.<Specification<MunicipalRequest>>any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        CallerContext funcionario = new CallerContext("func", "Funcionario");
        LocalDate day = LocalDate.of(2026, 11, 6);

        requestService.list(query().page(0).size(1).q("a".repeat(100)).from(day).to(day).build(), funcionario);
        requestService.list(query().size(50).open("false").sector("villa-ralco").sort("folio,DESC").build(), funcionario);
        requestService.list(query().q("   ").open(" ").sector("").sort("status").build(), funcionario);

        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(3)).findAll(ArgumentMatchers.<Specification<MunicipalRequest>>any(), pages.capture());
        assertEquals(List.of(1, 50, 20), pages.getAllValues().stream().map(Pageable::getPageSize).toList());
        assertTrue(pages.getAllValues().stream().allMatch(page -> page.getSort().isUnsorted()));
    }

    private void assertListRejected(TestListQueries params, CallerContext caller, String message) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> requestService.list(params.build(), caller));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(message, error.getReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Funcionario", "Admin", "Auditor"})
    void soloElVecinoCreaTramites(String role) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                create(new CreateRequestDto("Hueco", "bache", "Calle 1"), new CallerContext("u-1", role)));

        assertEquals(403, ex.getStatusCode().value());
        assertEquals("Solo un Vecino puede crear un tramite", ex.getReason());
        verify(folios, never()).next(anyInt());
        verify(repository, never()).save(any());
    }

    @Test
    void changeStatus404SiNoExiste() {
        when(repository.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("no-existe", "ADMITIDO", null, null, new CallerContext("func", "Funcionario")));
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
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(current)));

        MunicipalRequest updated = requestService.changeStatus("id-1", rawTarget, reason, null, caller);
        assertEquals(expected, updated.getStatus());
        if (expected == RequestStatus.RECHAZADO) {
            assertEquals(reason, updated.getRejectionReason());
        }
    }

    private void assertBadProcedureType(String procedureType, CallerContext caller) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                create(new CreateRequestDto("x", procedureType, "Calle 1"), caller));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals(
                "procedureType invalido. Debe ser minusculas, numeros, guion o guion bajo (maximo 40 caracteres)",
                ex.getReason()
        );
    }

    private void assertConflict(RequestStatus from, String rawTarget, CallerContext caller) {
        MunicipalRequest current = requestWithStatus(from);
        when(repository.findByIdForUpdate("id-1")).thenReturn(Optional.of(responsible(current)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                requestService.changeStatus("id-1", rawTarget, "motivo", null, caller));
        assertEquals(409, ex.getStatusCode().value());
    }

    /** El trámite queda a cargo del Funcionario "func" que operan estos tests (EP3-04, sección 3). */
    private static MunicipalRequest responsible(MunicipalRequest request) {
        request.assignTo("func", "Funcionario");
        return request;
    }

    private static MunicipalRequest requestWithStatus(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "oid");
        request.setStatus(status);
        return request;
    }
}
