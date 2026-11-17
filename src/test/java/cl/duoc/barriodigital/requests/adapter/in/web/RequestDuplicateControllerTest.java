package cl.duoc.barriodigital.requests.adapter.in.web;

import cl.duoc.barriodigital.requests.infrastructure.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.application.usecase.DuplicateCandidate;
import cl.duoc.barriodigital.requests.application.usecase.DuplicateCheckService;
import cl.duoc.barriodigital.requests.application.usecase.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.application.usecase.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.application.usecase.RequestActivityService;
import cl.duoc.barriodigital.requests.application.usecase.RequestAssignmentService;
import cl.duoc.barriodigital.requests.application.usecase.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.application.usecase.RequestService;
import cl.duoc.barriodigital.requests.application.usecase.StaffMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP de los posibles duplicados (EP3-75): rutas, proyección mínima por rol y errores. */
@WebMvcTest({RequestDuplicateController.class, RequestController.class})
@Import(ClockConfig.class)
class RequestDuplicateControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private DuplicateCheckService duplicates;
    @MockBean private RequestService requestService;
    @MockBean private RequestCreationOrchestrationService creation;
    @MockBean private RequestActivityService activityService;
    @MockBean private ImageAttachmentSanitizer imageSanitizer;
    @MockBean private StaffMemberService staffMembers;
    @MockBean private RequestAssignmentService assignment;
    @MockBean private NotificationPreferenceService notificationPreferences;

    @Test
    void duplicatesEsUnaRutaLiteralQueNoCapturaElId() throws Exception {
        when(duplicates.forNewRequest(any(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/requests/duplicates")
                        .param("procedureType", "bache").param("latitude", "-37.9").param("longitude", "-71.6")
                        .param("sector", "ralco")
                        .header(CallerContext.HEADER_USER_ID, "vecino-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(duplicates).forNewRequest(eq("bache"), eq("-37.9"), eq("-71.6"), eq("ralco"), any());
        verifyNoInteractions(requestService);
    }

    @Test
    void elVecinoRecibeSoloLosCamposMinimosYSabeSiEsSuyo() throws Exception {
        when(duplicates.forNewRequest(any(), any(), any(), any(), any())).thenReturn(List.of(
                new DuplicateCandidate(stored("vecino-1"), 44.9),
                new DuplicateCandidate(stored("vecino-2"), null)
        ));

        mockMvc.perform(get("/api/requests/duplicates")
                        .param("procedureType", "bache").param("sector", "ralco")
                        .header(CallerContext.HEADER_USER_ID, "vecino-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(10))
                .andExpect(jsonPath("$[0].id").value(nullValue()))
                .andExpect(jsonPath("$[0].folio").value("BD-2026-000123"))
                .andExpect(jsonPath("$[0].procedureType").value("bache"))
                .andExpect(jsonPath("$[0].procedureLabel").value("Bache en calle"))
                .andExpect(jsonPath("$[0].status").value("INGRESADO"))
                .andExpect(jsonPath("$[0].sector").value("ralco"))
                .andExpect(jsonPath("$[0].distanceMeters").value(40))
                // 02:30 UTC del 12-nov todavía es 11-nov en Chile.
                .andExpect(jsonPath("$[0].createdDate").value("2026-11-11"))
                .andExpect(jsonPath("$[0].hasAssignee").value(nullValue()))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[1].distanceMeters").value(nullValue()))
                .andExpect(jsonPath("$[1].mine").value(false))
                .andExpect(jsonPath("$[0].solicitanteId").doesNotExist())
                .andExpect(jsonPath("$[0].solicitanteNombre").doesNotExist())
                .andExpect(jsonPath("$[0].solicitanteEmail").doesNotExist())
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].address").doesNotExist())
                .andExpect(jsonPath("$[0].locationReference").doesNotExist())
                .andExpect(jsonPath("$[0].latitude").doesNotExist())
                .andExpect(jsonPath("$[0].longitude").doesNotExist())
                .andExpect(jsonPath("$[0].assigneeId").doesNotExist());
    }

    @Test
    void elPersonalRecibeElIdYSiTieneResponsableSinMine() throws Exception {
        MunicipalRequest assigned = stored("vecino-2");
        assigned.assignTo("func-9", "Ana");
        when(duplicates.forRequest(eq("base-1"), any()))
                .thenReturn(List.of(new DuplicateCandidate(assigned, 45.0)));

        mockMvc.perform(get("/api/requests/base-1/duplicates")
                        .header(CallerContext.HEADER_USER_ID, "func-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(10))
                .andExpect(jsonPath("$[0].id").value(assigned.getId()))
                .andExpect(jsonPath("$[0].distanceMeters").value(50))
                .andExpect(jsonPath("$[0].hasAssignee").value(true))
                .andExpect(jsonPath("$[0].mine").value(nullValue()))
                .andExpect(jsonPath("$[0].assigneeName").doesNotExist())
                .andExpect(jsonPath("$[0].solicitanteNombre").doesNotExist());
    }

    @Test
    void losErroresDelServicioSalenConElCuerpoComun() throws Exception {
        when(duplicates.forNewRequest(any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica un punto o un sector"));

        mockMvc.perform(get("/api/requests/duplicates")
                        .param("procedureType", "bache")
                        .header(CallerContext.HEADER_USER_ID, "vecino-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Indica un punto o un sector"))
                .andExpect(jsonPath("$.path").value("/api/requests/duplicates"));
    }

    @Test
    void sinRolNiSiquieraLlegaAlServicio() throws Exception {
        mockMvc.perform(get("/api/requests/duplicates")
                        .param("procedureType", "bache").param("sector", "ralco")
                        .header(CallerContext.HEADER_USER_ID, "vecino-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));

        verifyNoInteractions(duplicates);
    }

    private static MunicipalRequest stored(String owner) {
        MunicipalRequest request = new MunicipalRequest(
                "Bache en calle — Ralco 1", "Hoyo grande frente a mi casa", "bache", "Calle Ralco 1",
                owner, "Vecina Pérez", "vecina@example.com", "Bache en calle",
                Instant.parse("2026-11-12T02:30:00Z"));
        request.assignFolio(Folio.format(2026, 123));
        request.placeLocation("ralco", "Frente al poste", -37.9, -71.6, LocationPrecision.EXACTO);
        ReflectionTestUtils.setField(request, "id", "dup-" + owner);
        return request;
    }
}
