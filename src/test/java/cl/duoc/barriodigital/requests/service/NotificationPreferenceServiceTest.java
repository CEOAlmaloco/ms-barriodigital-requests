package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.UserNotificationPreference;
import cl.duoc.barriodigital.requests.persistence.UserNotificationPreferenceRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock
    private UserNotificationPreferenceRepository repository;

    @InjectMocks
    private NotificationPreferenceService service;

    @Test
    void sinFilaElCorreoQuedaHabilitado() {
        when(repository.findById("oid-1")).thenReturn(Optional.empty());

        assertTrue(service.isEmailEnabled("oid-1"));
        assertTrue(service.get(new CallerContext("oid-1", "Vecino")).isEmailEnabled());
    }

    @Test
    void updateDesactivaYPersiste() {
        when(repository.findById("oid-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserNotificationPreference saved = service.update(false, new CallerContext("oid-1", "Vecino"));

        assertFalse(saved.isEmailEnabled());
        ArgumentCaptor<UserNotificationPreference> captor = ArgumentCaptor.forClass(UserNotificationPreference.class);
        verify(repository).save(captor.capture());
        assertEquals("oid-1", captor.getValue().getUserId());
        assertFalse(captor.getValue().isEmailEnabled());
    }

    @Test
    void sinUserIdEs400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.get(new CallerContext(null, "Vecino")));
        assertEquals(400, ex.getStatusCode().value());
    }
}
