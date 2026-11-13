package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.StaffMember;
import cl.duoc.barriodigital.requests.persistence.StaffMemberRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssigneeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaffMemberServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

    private final StaffMemberRepository staff = mock(StaffMemberRepository.class);
    private final StaffMemberService service = new StaffMemberService(staff, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void laPrimeraLlamadaCreaLaFilaConAmbasFechas() {
        when(staff.findById("oid-1")).thenReturn(Optional.empty());

        service.touch("oid-1", "Ana María", "Funcionario");

        ArgumentCaptor<StaffMember> saved = ArgumentCaptor.forClass(StaffMember.class);
        verify(staff).save(saved.capture());
        assertEquals("Ana María", saved.getValue().getName());
        assertEquals("Funcionario", saved.getValue().getRole());
        assertEquals(NOW, saved.getValue().getFirstSeenAt());
        assertEquals(NOW, saved.getValue().getLastSeenAt());
    }

    @Test
    void unaLlamadaPosteriorActualizaNombreRolYUltimaVezSinTocarLaPrimera() {
        Instant first = Instant.parse("2026-11-01T12:00:00Z");
        StaffMember existing = new StaffMember("oid-1", "Ana", "Funcionario", first);
        when(staff.findById("oid-1")).thenReturn(Optional.of(existing));

        service.touch("oid-1", "Ana María", "Admin");

        verify(staff, never()).save(any());
        assertEquals("Ana María", existing.getName());
        assertEquals("Admin", existing.getRole());
        assertEquals(first, existing.getFirstSeenAt());
        assertEquals(NOW, existing.getLastSeenAt());
    }

    @Test
    void unaLlamadaSinNombreConservaElNombreGuardadoYActualizaRolYUltimaVez() {
        StaffMember existing = new StaffMember("oid-1", "Ana María", "Funcionario",
                Instant.parse("2026-11-01T12:00:00Z"));
        when(staff.findById("oid-1")).thenReturn(Optional.of(existing));

        service.touch("oid-1", null, "Admin");

        assertEquals("Ana María", existing.getName());
        assertEquals("Admin", existing.getRole());
        assertEquals(NOW, existing.getLastSeenAt());
    }

    @Test
    void unaFilaNuevaSinNombreUsaElOidHastaQueLlegueElNombre() {
        when(staff.findById("oid-1")).thenReturn(Optional.empty());

        service.touch("oid-1", null, "Funcionario");

        ArgumentCaptor<StaffMember> saved = ArgumentCaptor.forClass(StaffMember.class);
        verify(staff).save(saved.capture());
        assertEquals("oid-1", saved.getValue().getName());
    }

    @Test
    void elNombreRealReemplazaAlOidProvisorio() {
        StaffMember existing = new StaffMember("oid-1", "oid-1", "Funcionario",
                Instant.parse("2026-11-01T12:00:00Z"));
        when(staff.findById("oid-1")).thenReturn(Optional.of(existing));

        service.touch("oid-1", "Ana María", "Funcionario");

        assertEquals("Ana María", existing.getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Funcionario", "Admin"})
    void laListaDeFuncionariosEntregaSoloIdYNombre(String role) {
        when(staff.findByRoleOrderByNameAsc("Funcionario")).thenReturn(List.of(
                new StaffMember("oid-a", "Ana", "Funcionario", NOW),
                new StaffMember("oid-b", "Bruno", "Funcionario", NOW)));

        List<AssigneeView> list = service.assignees(new CallerContext("u-1", role));

        assertEquals(List.of(new AssigneeView("oid-a", "Ana"), new AssigneeView("oid-b", "Bruno")), list);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Auditor"})
    void elVecinoYElAuditorNoVenLaLista(String role) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assignees(new CallerContext("u-1", role)));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("No tienes el rol necesario para este recurso", error.getReason());
        verifyNoInteractions(staff);
    }
}
