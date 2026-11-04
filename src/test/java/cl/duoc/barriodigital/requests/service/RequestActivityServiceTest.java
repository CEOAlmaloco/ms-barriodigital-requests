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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestActivityServiceTest {

    private final MunicipalRequestRepository requests = mock(MunicipalRequestRepository.class);
    private final RequestActivityRepository activity = mock(RequestActivityRepository.class);
    private final RequestActivityService service = new RequestActivityService(requests, activity);

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
