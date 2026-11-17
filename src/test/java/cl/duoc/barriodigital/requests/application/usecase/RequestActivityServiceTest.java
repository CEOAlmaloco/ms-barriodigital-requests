package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.adapter.out.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.RequestActivityAttachmentRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.adapter.in.web.CallerContext;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.RegisterGestionDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestActivityServiceTest {

    private final MunicipalRequestRepository requests = mock(MunicipalRequestRepository.class);
    private final RequestActivityRepository activity = mock(RequestActivityRepository.class);
    private final DomainEventRecorder events = mock(DomainEventRecorder.class);
    private final RequestActivityAttachmentRepository commentAttachments = mock(RequestActivityAttachmentRepository.class);
    private final RequestActivityService service =
            new RequestActivityService(requests, activity, events, commentAttachments);

    private static final CallerContext FUNCIONARIO = new CallerContext("func", "Funcionario", "Ana%20Mar%C3%ADa");

    @Test
    void elVecinoNoVeLaGestionNiElConteoDeLlamadas() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino", "Vecino"),
                RequestActivity.gestion(request.getId(), created.plusSeconds(60), "func", "Ana", "Funcionario",
                        ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "nota interna", created.plusSeconds(3600), null)
        ));

        ActividadView view = service.get(request.getId(), new CallerContext(request.getSolicitanteId(), "Vecino"));

        assertEquals(1, view.eventos().size());
        assertEquals(ActivityKind.CREADO, view.eventos().get(0).kind());
        assertNull(view.contacto());
        assertNull(view.tiempos().primeraAtencionSegundos());
    }

    @Test
    void elVecinoVeASuNombreEnSusEventosYAMunicipalidadEnLosDelStaff() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino-1", "Vecino Uno", "Vecino"),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(60), "oid-func", "Ana Funcionaria", "Funcionario",
                        RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null)
        ));

        ActividadView vecino = service.get(request.getId(), new CallerContext("vecino-1", "Vecino"));
        ActividadView staff = service.get(request.getId(), new CallerContext("aud-1", "Auditor"));

        assertEquals("vecino-1", vecino.eventos().get(0).actorId());
        assertEquals("Vecino Uno", vecino.eventos().get(0).actorName());
        assertNull(vecino.eventos().get(1).actorId());
        assertEquals("Municipalidad", vecino.eventos().get(1).actorName());
        assertEquals("oid-func", staff.eventos().get(1).actorId());
        assertEquals("Ana Funcionaria", staff.eventos().get(1).actorName());
    }

    @Test
    void elRolDelActorEsSoloParaStaffInclusoEnLasMarcasDelVecino() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino-1", "Vecino Uno", "Vecino"),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(60), "adm-1", "Admin", "Admin",
                        RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, "Reclamo en la OIRS")
        ));

        ActividadView vecino = service.get(request.getId(), new CallerContext("vecino-1", "Vecino"));
        ActividadView auditor = service.get(request.getId(), new CallerContext("aud-1", "Auditor"));

        assertNull(vecino.eventos().get(0).actorRole());
        assertNull(vecino.eventos().get(1).actorRole());
        assertEquals("Vecino", auditor.eventos().get(0).actorRole());
        assertEquals("Admin", auditor.eventos().get(1).actorRole());
    }

    @Test
    void elMotivoDeIntervencionNoLlegaAlVecinoAunqueLaFilaSeaPublica() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(60), "adm-1", "Admin", "Admin",
                        RequestStatus.INGRESADO, RequestStatus.RECHAZADO, "Duplicado", "Reclamo en la OIRS")
        ));

        ActividadView vecino = service.get(request.getId(), new CallerContext("vecino-1", "Vecino"));
        ActividadView auditor = service.get(request.getId(), new CallerContext("aud-1", "Auditor"));

        assertNull(vecino.eventos().get(0).interventionReason());
        assertEquals("Duplicado", vecino.eventos().get(0).reason());
        assertEquals(false, vecino.eventos().get(0).summary().contains("OIRS"));
        assertEquals("Reclamo en la OIRS", auditor.eventos().get(0).interventionReason());
    }

    @Test
    void elAdminRegistraUnaGestionConMotivoDeIntervencion() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        service.registerGestion(request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null, " Vecino reclamó "),
                new CallerContext("adm-1", "Admin", "Admin"));

        ArgumentCaptor<RequestActivity> saved = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(saved.capture());
        assertEquals("Vecino reclamó", saved.getValue().getInterventionReason());
    }

    @Test
    void elAdminSinMotivoRecibe400AntesDeBuscarElTramite() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                "no-existe",
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                new CallerContext("adm-1", "Admin")));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("interventionReason es obligatorio para el Admin", error.getReason());
        verifyNoInteractions(requests, activity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Auditor"})
    void elVecinoYElAuditorNoRegistranGestionesYConservanElTextoActual(String role) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                "id-1",
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                new CallerContext("u-1", role)));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo un Funcionario puede registrar una gestión", error.getReason());
    }

    @Test
    void cadaRolVeUnaSolaFilaDeLaAsignacionYElStaffElTiempoDeAsignacion() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant assignedAt = request.getCreatedAt().plusSeconds(300);
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), request.getCreatedAt(), "vecino-1", "Vecino Uno", "Vecino"),
                RequestActivity.assigned(request.getId(), assignedAt, "func", "Ana", "Funcionario", "func", "Ana", null, null, null),
                RequestActivity.assignmentMilestone(request.getId(), assignedAt)
        ));

        ActividadView vecino = service.get(request.getId(), new CallerContext("vecino-1", "Vecino"));
        ActividadView staff = service.get(request.getId(), new CallerContext("adm-1", "Admin"));

        assertEquals(2, vecino.eventos().size());
        ActividadView.EventoView milestone = vecino.eventos().get(1);
        assertEquals("Tu solicitud fue asignada para su atención", milestone.summary());
        assertNull(milestone.actorId());
        assertEquals("Municipalidad", milestone.actorName());
        assertNull(milestone.assigneeId());
        assertNull(vecino.tiempos().primeraAsignacionSegundos());
        assertEquals(false, vecino.tiempos().noCalculado().stream().anyMatch(text -> text.contains("asignación")));

        assertEquals(2, staff.eventos().size());
        ActividadView.EventoView internal = staff.eventos().get(1);
        assertEquals(ActivityVisibility.INTERNO, internal.visibility());
        assertEquals("Tomó el trámite", internal.summary());
        assertEquals("func", internal.assigneeId());
        assertEquals("Ana", internal.assigneeName());
        assertEquals(300, staff.tiempos().primeraAsignacionSegundos());
        assertEquals(false, staff.tiempos().noCalculado().stream().anyMatch(text -> text.contains("asignación")));
    }

    @Test
    void sinAsignacionElStaffVeQueElTiempoNoSeCalcula() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), request.getCreatedAt(), "vecino-1", "Vecino Uno", "Vecino")));

        ActividadView staff = service.get(request.getId(), new CallerContext("func", "Funcionario"));

        assertNull(staff.tiempos().primeraAsignacionSegundos());
        assertEquals(true, staff.tiempos().noCalculado()
                .contains("Tiempo de asignación: el expediente todavía no tiene responsable."));
    }

    @Test
    void elFuncionarioQueNoEsElResponsableNoRegistraGestiones() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                new CallerContext("otro-func", "Funcionario")));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo el responsable del tramite puede operarlo", error.getReason());
        verify(activity, never()).save(any(RequestActivity.class));
    }

    @Test
    void elFuncionarioVeElIntentoYLaPrimeraAtencion() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino", "Vecino"),
                RequestActivity.gestion(request.getId(), created.plusSeconds(90), "func", "Ana", "Funcionario",
                        ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null, null)
        ));

        ActividadView view = service.get(request.getId(), new CallerContext("func", "Funcionario", "Ana"));

        assertEquals(2, view.eventos().size());
        assertEquals(1, view.contacto().intentos());
        assertEquals(90, view.tiempos().primeraAtencionSegundos());
    }

    @Test
    void unVecinoAjenoRecibe404() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.get(request.getId(), new CallerContext("otro", "Vecino")));

        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void elAuditorNoRegistraGestionesYElFuncionarioSi() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(activity.findByRequestIdOrderByActivitySequenceAsc(request.getId())).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                new CallerContext("aud", "Auditor", "Auditor")));

        service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "  ", null),
                new CallerContext("func", "Funcionario", "Ana"));
        verify(activity).save(any(RequestActivity.class));
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"RESUELTO", "RECHAZADO"})
    void unaGestionEnUnTramiteCerradoResponde409SinGuardar(RequestStatus closed) {
        MunicipalRequest request = request();
        request.setStatus(closed);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                FUNCIONARIO));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("No se puede registrar una gestion en un tramite cerrado", error.getReason());
        verify(activity, never()).save(any(RequestActivity.class));
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"INGRESADO", "ADMITIDO", "EN_GESTION", "EN_TERRENO"})
    void unaGestionEnUnTramiteAbiertoSeGuardaConElNombreDecodificado(RequestStatus open) {
        MunicipalRequest request = request();
        request.setStatus(open);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        Instant next = Instant.now().plusSeconds(3600);

        service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, next),
                FUNCIONARIO);

        ArgumentCaptor<RequestActivity> saved = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity).save(saved.capture());
        assertEquals("Ana María", saved.getValue().getActorName());
        assertEquals(next, saved.getValue().getNextAttemptAt());
    }

    @ParameterizedTest
    @ValueSource(longs = {-3600, 0})
    void unProximoIntentoQueNoEsFuturoResponde400AntesDeBuscarElTramite(long offsetSeconds) {
        Instant notFuture = Instant.now().plusSeconds(offsetSeconds);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                "no-existe",
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, notFuture),
                FUNCIONARIO));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("nextAttemptAt debe ser una fecha futura", error.getReason());
        verifyNoInteractions(requests, activity);
    }

    @Test
    void elRolSeRevisaAntesQueLaFechaYLaFechaAntesQueElTramite() {
        RegisterGestionDto past = new RegisterGestionDto(
                ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, Instant.now().minusSeconds(60));
        RegisterGestionDto future = new RegisterGestionDto(
                ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, Instant.now().plusSeconds(3600));
        when(requests.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());

        ResponseStatusException vecino = assertThrows(ResponseStatusException.class,
                () -> service.registerGestion("no-existe", past, new CallerContext("vecino-1", "Vecino")));
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.registerGestion("no-existe", future, FUNCIONARIO));

        assertEquals(403, vecino.getStatusCode().value());
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    void elTiempoMunicipalSaleDeLasEtapasYNoDelCierre() {
        MunicipalRequest request = request();
        request.setStatus(RequestStatus.RESUELTO);
        Instant created = request.getCreatedAt();
        List<RequestActivity> events = List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino", "Vecino"),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(100), "func", "Ana", "Funcionario",
                        RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(400), "func", "Ana", "Funcionario",
                        RequestStatus.EN_TERRENO, RequestStatus.RESUELTO, null, null)
        );

        ActividadView.TiemposView tiempos = RequestActivityService.tiempos(request, events, true, created.plusSeconds(999));

        assertEquals(300, tiempos.gestionMunicipalSegundos());
        assertEquals(400, tiempos.totalResolucionSegundos());
        assertNull(tiempos.desdeElIngresoSegundos());
    }

    private static final CallerContext TITULAR = new CallerContext("vecino-1", "Vecino", "Vecino%20Uno");

    @Test
    void comentarSinIdentidadResponde400() {
        assertStatus(400, () -> service.comment("x", "Hola", null, new CallerContext(null, "Vecino")));
        verifyNoInteractions(requests, activity, events, commentAttachments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Funcionario", "Admin", "Auditor"})
    void soloElVecinoComentaYElRolSeRevisaAntesQueElTexto(String role) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.comment("x", " ", null, new CallerContext("u1", role)));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo el Vecino titular puede comentar el tramite", error.getReason());
        verifyNoInteractions(requests, activity, events, commentAttachments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void elTextoVacioResponde400AntesDeBuscarElTramite(String text) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.comment("no-existe", text, null, TITULAR));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("text es obligatorio", error.getReason());
        assertStatus(400, () -> service.comment("no-existe", null, null, TITULAR));
        verifyNoInteractions(requests);
    }

    @Test
    void elTextoAdmite500CaracteresSinContarLosExtremosYRechaza501() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(activity.save(any(RequestActivity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.comment(request.getId(), "  " + "a".repeat(500) + "  ", null, TITULAR);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.comment(request.getId(), "a".repeat(501), null, TITULAR));
        assertEquals(400, error.getStatusCode().value());
        assertEquals("text no puede superar 500 caracteres", error.getReason());
    }

    @Test
    void unTramiteInexistenteResponde404() {
        when(requests.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());

        assertStatus(404, () -> service.comment("no-existe", "Hola", null, TITULAR));
    }

    @Test
    void otroVecinoResponde403AunqueElTramiteEsteCerrado() {
        MunicipalRequest request = request();
        request.setStatus(RequestStatus.RESUELTO);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.comment(request.getId(), "Hola", null, new CallerContext("vecino-2", "Vecino")));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo el titular del tramite puede comentarlo", error.getReason());
        verify(activity, never()).save(any(RequestActivity.class));
    }

    @Test
    void elTopeSeCuentaConLaFilaBloqueadaYNoGuardaNada() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(activity.countByRequestIdAndKind(request.getId(), ActivityKind.COMENTARIO)).thenReturn(20L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.comment(request.getId(), "Hola", null, TITULAR));

        assertEquals(409, error.getStatusCode().value());
        var order = inOrder(requests, activity);
        order.verify(requests).findByIdForUpdate(request.getId());
        order.verify(activity).countByRequestIdAndKind(request.getId(), ActivityKind.COMENTARIO);
        verify(activity, never()).save(any(RequestActivity.class));
        verifyNoInteractions(events, commentAttachments);
    }

    private static void assertStatus(int status, Executable call) {
        assertEquals(status, assertThrows(ResponseStatusException.class, call).getStatusCode().value());
    }

    /** Trámite del vecino "vecino-1", a cargo del Funcionario "func" que operan estos tests. */
    private static MunicipalRequest request() {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino", "v@example.com", "Bache");
        request.assignTo("func", "Ana");
        return request;
    }
}
