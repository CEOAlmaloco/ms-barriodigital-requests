package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las respuestas de trámites salen proyectadas según el rol (contrato EP3-04, sección 6.1). */
@WebMvcTest(RequestController.class)
class RequestControllerViewTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequestService requestService;

    @MockBean
    private RequestCreationOrchestrationService creation;

    @MockBean
    private RequestActivityService activityService;

    @MockBean
    private ImageAttachmentSanitizer imageSanitizer;

    @Test
    void elDetalleNoEntregaElCorreoAlAuditorYSiAlFuncionario() throws Exception {
        when(requestService.getById(eq("id-1"), any())).thenReturn(stored());

        mockMvc.perform(get("/api/requests/id-1")
                        .header(CallerContext.HEADER_USER_ID, "aud-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folio").value("BD-2026-000123"))
                .andExpect(jsonPath("$.solicitanteEmail").value(nullValue()))
                .andExpect(jsonPath("$.hasAssignee").value(false));

        mockMvc.perform(get("/api/requests/id-1")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitanteEmail").value("vecino@example.com"));
    }

    @Test
    void elListadoTambienSaleProyectado() throws Exception {
        when(requestService.list(isNull(), isNull(), isNull(), any())).thenReturn(List.of(stored()));

        mockMvc.perform(get("/api/requests")
                        .header(CallerContext.HEADER_USER_ID, "aud-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].folio").value("BD-2026-000123"))
                .andExpect(jsonPath("$[0].solicitanteEmail").value(nullValue()))
                .andExpect(jsonPath("$[0].assigneeId").value(nullValue()));
    }

    private static MunicipalRequest stored() {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino",
                "vecino@example.com");
        request.assignFolio("BD-2026-000123");
        return request;
    }
}
