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
}
