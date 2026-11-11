package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.UserNotificationPreference;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestAssignmentService;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestController.class)
@Import(ClockConfig.class)
class NotificationPreferenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private RequestService requestService;
    @MockBean private RequestCreationOrchestrationService creation;
    @MockBean private RequestActivityService activityService;
    @MockBean private ImageAttachmentSanitizer imageSanitizer;
    @MockBean private StaffMemberService staffMembers;
    @MockBean private RequestAssignmentService assignment;
    @MockBean private NotificationPreferenceService notificationPreferences;

    @Test
    void getDevuelvePreferencia() throws Exception {
        when(notificationPreferences.get(any()))
                .thenReturn(new UserNotificationPreference("oid-1", true, Instant.parse("2026-11-11T12:00:00Z")));

        mockMvc.perform(get("/api/requests/me/notification-preferences")
                        .header("X-User-Id", "oid-1")
                        .header("X-User-Roles", "Vecino"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true));
    }

    @Test
    void putGuardaPreferencia() throws Exception {
        when(notificationPreferences.update(eq(false), any()))
                .thenReturn(new UserNotificationPreference("oid-1", false, Instant.parse("2026-11-11T12:00:00Z")));

        mockMvc.perform(put("/api/requests/me/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "oid-1")
                        .header("X-User-Roles", "Vecino")
                        .content("{\"emailEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false));

        verify(notificationPreferences).update(eq(false), any());
    }
}
