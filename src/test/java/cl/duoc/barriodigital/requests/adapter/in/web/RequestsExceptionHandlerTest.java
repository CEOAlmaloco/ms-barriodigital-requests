package cl.duoc.barriodigital.requests.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestsExceptionHandlerTest {

    private final RequestsExceptionHandler handler =
            new RequestsExceptionHandler(Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void dataAccessNoExponeSqlNiOraAlCliente() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/requests/id-1/cancel");
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement [ORA-02290: check constraint (BARRIODIGITAL.SYS_C0036717) violated]");

        ResponseEntity<Map<String, Object>> response = handler.dataAccess(ex, request);

        assertEquals(500, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(RequestsExceptionHandler.PERSISTENCE_FAILED, body.get("message"));
        assertFalse(String.valueOf(body.get("message")).contains("ORA-"));
        assertFalse(String.valueOf(body.get("message")).contains("SQL"));
        assertEquals("/api/requests/id-1/cancel", body.get("path"));
    }
}
