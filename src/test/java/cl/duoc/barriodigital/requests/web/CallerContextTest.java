package cl.duoc.barriodigital.requests.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerContextTest {

    @ParameterizedTest
    @CsvSource({
            "Vecino, Vecino",
            "ROLE_Funcionario, Funcionario",
            "' admin ', Admin",
            "'Auditor,Auditor', Auditor",
            "'Vecino,OtroRolDeEntra', Vecino"
    })
    void reconoceUnSoloRolConOSinPrefijoEIgnoraValoresQueNoSonRoles(String header, String role) {
        CallerContext caller = new CallerContext("u-1", header);

        assertEquals(role, caller.role());
        assertDoesNotThrow(caller::requireSingleRole);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "OtroRolDeEntra"})
    void sinNingunoDeLosCuatroRolesResponde403(String header) {
        CallerContext caller = new CallerContext("u-1", header);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, caller::requireSingleRole);

        assertEquals(403, error.getStatusCode().value());
        assertEquals("No tienes un rol asignado en BarrioDigital", error.getReason());
        assertNull(caller.role());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Funcionario,Vecino", "Admin,ROLE_Auditor"})
    void conMasDeUnRolResponde403YNoCuentaComoNinguno(String header) {
        CallerContext caller = new CallerContext("u-1", header);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, caller::requireSingleRole);

        assertEquals("El usuario debe tener un solo rol", error.getReason());
        assertNull(caller.role());
        assertFalse(caller.isFuncionario());
        assertFalse(caller.isStaff());
        assertTrue(caller.mustFilterOwnRequests());
    }

    @Test
    void cadaRolTieneSuPredicado() {
        assertTrue(new CallerContext("u", "Vecino").isVecino());
        assertTrue(new CallerContext("u", "Funcionario").isFuncionario());
        assertTrue(new CallerContext("u", "Admin").isAdmin());
        assertTrue(new CallerContext("u", "Auditor").isAuditor());
        assertEquals(List.of(true, true, true, false), List.of(
                new CallerContext("u", "Funcionario").isStaff(),
                new CallerContext("u", "Admin").isStaff(),
                new CallerContext("u", "Auditor").isStaff(),
                new CallerContext("u", "Vecino").isStaff()));
    }
}
