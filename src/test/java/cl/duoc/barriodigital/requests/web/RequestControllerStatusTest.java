package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestAssignmentService;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.service.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestController.class)
@Import(ClockConfig.class)
class RequestControllerStatusTest {

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
    void putStatusDevuelve200ConRecurso() throws Exception {
        MunicipalRequest updated = new MunicipalRequest("t", "d", "bache", "dir", "oid");
        updated.setStatus(RequestStatus.ADMITIDO);
        when(requestService.changeStatus(eq("id-1"), eq("ADMITIDO"), isNull(), isNull(), any()))
                .thenReturn(updated);

        mockMvc.perform(put("/api/requests/id-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "func-1")
                        .header("X-User-Roles", "Funcionario")
                        .content("{\"status\":\"ADMITIDO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITIDO"));

        verify(requestService).changeStatus(eq("id-1"), eq("ADMITIDO"), isNull(), isNull(), any());
    }

    @Test
    void putStatusPropaga409() throws Exception {
        when(requestService.changeStatus(eq("id-1"), eq("EN_TERRENO"), isNull(), isNull(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "No se puede pasar de INGRESADO a EN_TERRENO"));

        mockMvc.perform(put("/api/requests/id-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "func-1")
                        .header("X-User-Roles", "Funcionario")
                        .content("{\"status\":\"EN_TERRENO\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void putStatusPropaga400SiFaltaMotivo() throws Exception {
        when(requestService.changeStatus(eq("id-1"), eq("RECHAZADO"), isNull(), isNull(), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "rejectionReason es obligatorio cuando el estado es RECHAZADO"));

        mockMvc.perform(put("/api/requests/id-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "func-1")
                        .header("X-User-Roles", "Funcionario")
                        .content("{\"status\":\"RECHAZADO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postRatingDevuelve200() throws Exception {
        MunicipalRequest rated = new MunicipalRequest("t", "d", "bache", "dir", "oid-vecino");
        rated.setStatus(RequestStatus.RESUELTO);
        rated.applyRating(5, "Bien");
        when(requestService.rate(eq("id-1"), eq(5), eq("Bien"), any())).thenReturn(rated);

        mockMvc.perform(post("/api/requests/id-1/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "oid-vecino")
                        .header("X-User-Roles", "Vecino")
                        .content("{\"score\":5,\"comment\":\"Bien\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satisfactionScore").value(5))
                .andExpect(jsonPath("$.satisfactionComment").value("Bien"));

        verify(requestService).rate(eq("id-1"), eq(5), eq("Bien"), any());
    }

    @Test
    void postRatingPropaga409() throws Exception {
        when(requestService.rate(eq("id-1"), eq(3), isNull(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "El tramite ya fue calificado"));

        mockMvc.perform(post("/api/requests/id-1/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "oid-vecino")
                        .header("X-User-Roles", "Vecino")
                        .content("{\"score\":3}"))
                .andExpect(status().isConflict());
    }
}
