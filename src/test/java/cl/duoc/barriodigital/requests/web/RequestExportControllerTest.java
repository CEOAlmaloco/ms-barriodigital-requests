package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.RequestExportService;
import cl.duoc.barriodigital.requests.service.RequestListQuery;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP de GET /api/requests/export (EP3-63): descarga privada en CSV y los filtros del listado. */
@WebMvcTest(RequestExportController.class)
@Import(RequestExportControllerTest.FixedClock.class)
class RequestExportControllerTest {

    @TestConfiguration
    static class FixedClock {
        // 02:30 UTC del 15-nov todavía es 14-nov en Chile.
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-15T02:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequestExportService exports;

    @MockBean
    private StaffMemberService staffMembers;

    @Test
    void descargaUnCsvPrivadoConElNombreDelDiaEnChile() throws Exception {
        when(exports.export(any(), any())).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/api/requests/export")
                        .header(CallerContext.HEADER_USER_ID, "vecino-a")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"tramites-2026-11-14.csv\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("﻿\"Folio\";"), body);
    }

    @Test
    void reenviaLosFiltrosYElOrdenSinPaginaAlServicio() throws Exception {
        when(exports.export(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/requests/export")
                        .param("status", "EN_GESTION").param("from", "2026-11-01").param("to", "2026-11-14")
                        .param("assignee", "me").param("open", "true").param("q", "poste")
                        .param("procedureType", "bache").param("sector", "ralco").param("contactDue", "true")
                        .param("sort", "folio,desc")
                        .param("page", "3").param("size", "1")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk());

        ArgumentCaptor<RequestListQuery> params = ArgumentCaptor.forClass(RequestListQuery.class);
        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(exports).export(params.capture(), caller.capture());
        assertEquals(new RequestListQuery(RequestStatus.EN_GESTION, LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-14"), "me", "true", "poste", "bache", "ralco", "true", null, null,
                "folio,desc"),
                params.getValue());
        assertEquals("func-1", caller.getValue().userId());
        assertTrue(caller.getValue().isFuncionario());
        assertNull(params.getValue().page());
    }

    @Test
    void elRechazoDelServicioLlegaConSuCodigo() throws Exception {
        when(exports.export(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La exportacion supera 5000 tramites. Agrega filtros"));

        mockMvc.perform(get("/api/requests/export")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unTokenConDosRolesNoLlegaALaExportacion() throws Exception {
        mockMvc.perform(get("/api/requests/export")
                        .header(CallerContext.HEADER_USER_ID, "x")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino,Funcionario"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(exports);
    }
}
