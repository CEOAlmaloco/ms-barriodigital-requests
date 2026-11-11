package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.web.dto.RegisterGestionDto;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestActivityServiceTest {

    private final MunicipalRequestRepository requests = mock(MunicipalRequestRepository.class);
    private final RequestActivityRepository activity = mock(RequestActivityRepository.class);
    private final RequestActivityService service = new RequestActivityService(requests, activity);

    private static final CallerContext FUNCIONARIO = new CallerContext("func", "Funcionario", "Ana%20Mar%C3%ADa");

    @Test
    void elVecinoNoVeLaGestionNiElConteoDeLlamadas() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino"),
                RequestActivity.gestion(request.getId(), created.plusSeconds(60), "func", "Ana",
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
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino-1", "Vecino Uno"),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(60), "oid-func", "Ana Funcionaria",
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
    void elMotivoDeIntervencionNoLlegaAlVecinoAunqueLaFilaSeaPublica() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(60), "adm-1", "Admin",
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
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), request.getCreatedAt(), "vecino-1", "Vecino Uno"),
                RequestActivity.assigned(request.getId(), assignedAt, "func", "Ana", "func", "Ana", null, null, null),
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
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), request.getCreatedAt(), "vecino-1", "Vecino Uno")));

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
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino"),
                RequestActivity.gestion(request.getId(), created.plusSeconds(90), "func", "Ana",
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
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of());

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
                RequestActivity.created(request.getId(), created, "vecino", "Vecino"),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(100), "func", "Ana",
                        RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(400), "func", "Ana",
                        RequestStatus.EN_TERRENO, RequestStatus.RESUELTO, null, null)
        );

        ActividadView.TiemposView tiempos = RequestActivityService.tiempos(request, events, true, created.plusSeconds(999));

        assertEquals(300, tiempos.gestionMunicipalSegundos());
        assertEquals(400, tiempos.totalResolucionSegundos());
        assertNull(tiempos.desdeElIngresoSegundos());
    }

    /** Trámite del vecino "vecino-1", a cargo del Funcionario "func" que operan estos tests. */
    private static MunicipalRequest request() {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino", "v@example.com", "Bache");
        request.assignTo("func", "Ana");
        return request;
    }
}
