package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.service.StaffMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StaffMemberInterceptorTest {

    private final StaffMemberService staff = mock(StaffMemberService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T15:00:00Z"));
    private final StaffMemberInterceptor interceptor = new StaffMemberInterceptor(staff, clock);

    @Test
    void registraAlStaffConElNombreDecodificado() {
        assertTrue(call("oid-1", "Funcionario", "Ana%20Mar%C3%ADa"));

        verify(staff).touch("oid-1", "Ana María", "Funcionario");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Funcionario,Admin", ""})
    void noRegistraAlVecinoNiAQuienNoTieneUnSoloRol(String roles) {
        call("oid-1", roles, "Nombre");

        verifyNoInteractions(staff);
    }

    @Test
    void escribeUnaVezCada15MinutosPorOid() {
        call("oid-1", "Funcionario", "Ana");
        clock.plusMinutes(14);
        call("oid-1", "Funcionario", "Ana");
        verify(staff, times(1)).touch("oid-1", "Ana", "Funcionario");

        clock.plusMinutes(1);
        call("oid-1", "Funcionario", "Ana");
        verify(staff, times(2)).touch("oid-1", "Ana", "Funcionario");
    }

    @Test
    void unCambioDeNombreODeRolSeEscribeEnLaSiguienteLlamada() {
        call("oid-1", "Funcionario", "Ana");
        call("oid-1", "Funcionario", "Ana%20Mar%C3%ADa");
        call("oid-1", "Admin", "Ana%20Mar%C3%ADa");

        verify(staff).touch("oid-1", "Ana", "Funcionario");
        verify(staff).touch("oid-1", "Ana María", "Funcionario");
        verify(staff).touch("oid-1", "Ana María", "Admin");
    }

    @Test
    void unFalloDelRegistroNoBloqueaLaLlamadaYSeReintentaEnLaSiguiente() {
        doThrow(new DataIntegrityViolationException("PK staff_member"))
                .doNothing()
                .when(staff).touch(any(), any(), any());

        assertTrue(call("oid-1", "Funcionario", "Ana"));
        assertTrue(call("oid-1", "Funcionario", "Ana"));

        verify(staff, times(2)).touch("oid-1", "Ana", "Funcionario");
    }

    @Test
    void unaLlamadaSinNombreRegistraSinNombreYNoUsaElOidComoNombre() {
        call("oid-1", "Funcionario", null);

        verify(staff).touch("oid-1", null, "Funcionario");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%E0%A4%A", "Ana%0AFalsa"})
    void unNombreVacioOIlegibleCuentaComoAusente(String header) {
        call("oid-1", "Funcionario", header);

        verify(staff).touch("oid-1", null, "Funcionario");
    }

    @Test
    void unaLecturaSinNombreNoVuelveAEscribirNiPisaElNombreConocido() {
        call("oid-1", "Funcionario", "Ana");
        call("oid-1", "Funcionario", null);
        clock.plusMinutes(15);
        call("oid-1", "Funcionario", null);

        verify(staff, times(1)).touch("oid-1", "Ana", "Funcionario");
        verify(staff, times(1)).touch("oid-1", null, "Funcionario");
        verify(staff, never()).touch("oid-1", "oid-1", "Funcionario");
    }

    @Test
    void elNombreLlegaDespuesDeUnaLlamadaSinNombreYSeEscribeAlInstante() {
        call("oid-1", "Funcionario", null);
        call("oid-1", "Funcionario", "Ana");

        verify(staff).touch("oid-1", null, "Funcionario");
        verify(staff).touch("oid-1", "Ana", "Funcionario");
    }

    private boolean call(String oid, String roles, String name) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/requests");
        request.addHeader(CallerContext.HEADER_USER_ID, oid);
        request.addHeader(CallerContext.HEADER_USER_ROLES, roles);
        if (name != null) {
            request.addHeader(CallerContext.HEADER_USER_NAME, name);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void plusMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
