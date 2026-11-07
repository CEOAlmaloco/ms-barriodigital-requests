package cl.duoc.barriodigital.requests.service;

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
                        ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, "nota interna", created.plusSeconds(3600))
        ));

        ActividadView view = service.get(request.getId(), new CallerContext(request.getSolicitanteId(), "Vecino"));

        assertEquals(1, view.eventos().size());
        assertEquals(ActivityKind.CREADO, view.eventos().get(0).kind());
        assertNull(view.contacto());
        assertNull(view.tiempos().primeraAtencionSegundos());
    }

    @Test
    void elFuncionarioVeElIntentoYLaPrimeraAtencion() {
        MunicipalRequest request = request();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        Instant created = request.getCreatedAt();
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of(
                RequestActivity.created(request.getId(), created, "vecino", "Vecino"),
                RequestActivity.gestion(request.getId(), created.plusSeconds(90), "func", "Ana",
                        ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null)
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
    void soloElFuncionarioRegistraUnaGestion() {
        MunicipalRequest request = request();
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(activity.findByRequestIdOrderByOccurredAtAsc(request.getId())).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.registerGestion(
                request.getId(),
                new RegisterGestionDto(ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, null),
                new CallerContext("admin", "Admin", "Admin")));

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
                        RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null),
                RequestActivity.statusChanged(request.getId(), created.plusSeconds(400), "func", "Ana",
                        RequestStatus.EN_TERRENO, RequestStatus.RESUELTO, null)
        );

        ActividadView.TiemposView tiempos = RequestActivityService.tiempos(request, events, true, created.plusSeconds(999));

        assertEquals(300, tiempos.gestionMunicipalSegundos());
        assertEquals(400, tiempos.totalResolucionSegundos());
        assertNull(tiempos.desdeElIngresoSegundos());
    }

    private static MunicipalRequest request() {
        return new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino", "v@example.com", "Bache");
    }
}
