package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    @Mock
    private MunicipalRequestRepository repository;

    @InjectMocks
    private RequestService requestService;

    @Test
    void createGuardaEnEstadoIngresado() {
        when(repository.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MunicipalRequest created = requestService.create(
                new CreateRequestDto("Bache", "Hueco", "bache")
        );

        assertEquals(RequestStatus.INGRESADO, created.getStatus());
        assertEquals("Bache", created.getTitle());

        ArgumentCaptor<MunicipalRequest> captor = ArgumentCaptor.forClass(MunicipalRequest.class);
        verify(repository).save(captor.capture());
        assertEquals(RequestStatus.INGRESADO, captor.getValue().getStatus());
    }

    @Test
    void getByIdLanza404SiNoExiste() {
        when(repository.findById("no-existe")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> requestService.getById("no-existe"));
    }

    @Test
    void listDelegaFiltrosAlRepositorio() {
        when(repository.search(RequestStatus.INGRESADO, null, null)).thenReturn(List.of());
        requestService.list(RequestStatus.INGRESADO, null, null);
        verify(repository).search(RequestStatus.INGRESADO, null, null);
    }
}
