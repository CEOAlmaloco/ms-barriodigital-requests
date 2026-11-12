package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestAssignmentService;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestListQuery;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.service.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import cl.duoc.barriodigital.requests.web.dto.AssigneeView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las respuestas de trámites salen proyectadas según el rol (contrato EP3-04, sección 6.1). */
@WebMvcTest(RequestController.class)
@Import(ClockConfig.class)
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

    @MockBean
    private StaffMemberService staffMembers;

    @MockBean
    private RequestAssignmentService assignment;

    @MockBean
    private NotificationPreferenceService notificationPreferences;

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
    void elListadoSaleComoPaginaYProyectado() throws Exception {
        when(requestService.list(any(), any()))
                .thenReturn(new PageImpl<>(List.of(stored()), PageRequest.of(1, 2), 5));

        mockMvc.perform(get("/api/requests")
                        .header(CallerContext.HEADER_USER_ID, "aud-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].folio").value("BD-2026-000123"))
                .andExpect(jsonPath("$.content[0].solicitanteEmail").value(nullValue()))
                .andExpect(jsonPath("$.content[0].assigneeId").value(nullValue()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void elListadoPasaTodosLosParametrosAlServicio() throws Exception {
        when(requestService.list(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/requests")
                        .param("status", "EN_GESTION").param("from", "2026-11-01").param("to", "2026-11-06")
                        .param("assignee", "me").param("open", "true").param("q", "bache")
                        .param("procedureType", "alumbrado").param("sector", "pitril")
                        .param("page", "2").param("size", "10").param("sort", "status,desc")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        ArgumentCaptor<RequestListQuery> params = ArgumentCaptor.forClass(RequestListQuery.class);
        verify(requestService).list(params.capture(), any());
        assertEquals(new RequestListQuery(RequestStatus.EN_GESTION, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 6),
                "me", "true", "bache", "alumbrado", "pitril", 2, 10, "status,desc"), params.getValue());
    }

    @Test
    void assigneesEsUnaRutaLiteralQueNoCapturaElId() throws Exception {
        when(staffMembers.assignees(any())).thenReturn(List.of(new AssigneeView("oid-a", "Ana")));

        mockMvc.perform(get("/api/requests/assignees")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("oid-a"))
                .andExpect(jsonPath("$[0].name").value("Ana"))
                .andExpect(jsonPath("$[0].length()").value(2));

        verifyNoInteractions(requestService);
    }

    @Test
    void takeYAssigneeRespondenConLaVistaDelTramite() throws Exception {
        MunicipalRequest taken = stored();
        taken.assignTo("func-1", "Ana");
        when(assignment.take(eq("id-1"), any())).thenReturn(taken);
        when(assignment.assign(eq("id-1"), any(), any())).thenReturn(taken);

        mockMvc.perform(post("/api/requests/id-1/take")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAssignee").value(true))
                .andExpect(jsonPath("$.assigneeId").value("func-1"));

        mockMvc.perform(put("/api/requests/id-1/assignee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "adm-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Admin")
                        .content("{\"assigneeId\":\"func-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeName").value("Ana"));
    }

    @Test
    void unReasonDeMasDe500SeRechazaConValidAntesDelServicio() throws Exception {
        mockMvc.perform(put("/api/requests/id-1/assignee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "adm-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Admin")
                        .content("{\"assigneeId\":\"func-1\",\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assignment);
    }

    private static MunicipalRequest stored() {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino",
                "vecino@example.com");
        request.assignFolio("BD-2026-000123");
        return request;
    }
}
