package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    @Mock
    private MunicipalRequestRepository repository;

    @InjectMocks
    private RequestService requestService;

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
        assertEquals("Plaza Los Heroes", created.getAddress());
        assertEquals("bache", created.getProcedureType());
        assertTrue(created.getTitle().contains("Bache"));
        assertTrue(created.getTitle().contains("Plaza Los Heroes"));

        ArgumentCaptor<MunicipalRequest> captor = ArgumentCaptor.forClass(MunicipalRequest.class);
        verify(repository).save(captor.capture());
        assertEquals("oid-vecino-1", captor.getValue().getSolicitanteId());
    }

    @Test
    void createRechazaProcedureTypeDesconocido() {
        CallerContext vecino = new CallerContext("oid-1", "Vecino");
        assertThrows(ResponseStatusException.class, () ->
                requestService.create(new CreateRequestDto("x", "tipo-inventado", "Calle 1"), vecino));
    }

    @Test
    void getByIdVecinoNoVeTramiteAjeno() {
        MunicipalRequest other = new MunicipalRequest("t", "d", "bache", "dir", "otro-oid");
        when(repository.findById("id-1")).thenReturn(Optional.of(other));

        CallerContext vecino = new CallerContext("mi-oid", "Vecino");
        assertThrows(ResponseStatusException.class, () -> requestService.getById("id-1", vecino));
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
        when(repository.findById("no-existe")).thenReturn(Optional.empty());
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
        when(repository.findById("id-1")).thenReturn(Optional.of(current));

        MunicipalRequest updated = requestService.changeStatus("id-1", rawTarget, reason, caller);
        assertEquals(expected, updated.getStatus());
        if (expected == RequestStatus.RECHAZADO) {
            assertEquals(reason, updated.getRejectionReason());
        }
    }

    private void assertConflict(RequestStatus from, String rawTarget, CallerContext caller) {
        MunicipalRequest current = requestWithStatus(from);
        when(repository.findById("id-1")).thenReturn(Optional.of(current));
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
