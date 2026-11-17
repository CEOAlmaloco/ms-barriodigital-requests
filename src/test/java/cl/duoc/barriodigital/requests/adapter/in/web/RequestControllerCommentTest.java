package cl.duoc.barriodigital.requests.adapter.in.web;

import cl.duoc.barriodigital.requests.infrastructure.config.ClockConfig;
import cl.duoc.barriodigital.requests.application.usecase.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.application.usecase.NotificationPreferenceService;
import cl.duoc.barriodigital.requests.application.usecase.RequestActivityService;
import cl.duoc.barriodigital.requests.application.usecase.RequestAssignmentService;
import cl.duoc.barriodigital.requests.application.usecase.RequestAttachmentContent;
import cl.duoc.barriodigital.requests.application.usecase.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.application.usecase.RequestService;
import cl.duoc.barriodigital.requests.application.usecase.StaffMemberService;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.ActividadView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rutas del comentario del titular (EP3-64), con el sanitizador real de imágenes. */
@WebMvcTest(RequestController.class)
@Import({ClockConfig.class, ImageAttachmentSanitizer.class})
class RequestControllerCommentTest {

    private static final String COMMENTS = "/api/requests/req-1/comments";
    private static final ActividadView VIEW = new ActividadView("req-1", List.of(), null, null, List.of());

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequestService requestService;

    @MockBean
    private RequestCreationOrchestrationService creation;

    @MockBean
    private RequestActivityService activityService;

    @MockBean
    private StaffMemberService staffMembers;

    @MockBean
    private RequestAssignmentService assignment;

    @MockBean
    private NotificationPreferenceService notificationPreferences;

    @Test
    void jsonResponde201YPasaElTextoYLaIdentidad() throws Exception {
        when(activityService.comment(eq("req-1"), eq("Sigue igual"), isNull(), any())).thenReturn(VIEW);

        mockMvc.perform(post(COMMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .header(CallerContext.HEADER_USER_NAME, "Vecino%20Uno")
                        .content("{\"text\":\"Sigue igual\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("req-1"));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(activityService).comment(eq("req-1"), eq("Sigue igual"), isNull(), caller.capture());
        assertEquals("oid-1", caller.getValue().userId());
        assertEquals("Vecino", caller.getValue().role());
        assertEquals("Vecino%20Uno", caller.getValue().userName());
    }

    @Test
    void multipartConFotoLaSanitizaAntesDelServicio() throws Exception {
        when(activityService.comment(eq("req-1"), eq("Con foto"), any(), any())).thenReturn(VIEW);

        mockMvc.perform(multipart(COMMENTS)
                        .file(comment("Con foto"))
                        .file(new MockMultipartFile("attachment", "foto.png", "image/png", png()))
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isCreated());

        ArgumentCaptor<ImageAttachmentSanitizer.SanitizedImage> image =
                ArgumentCaptor.forClass(ImageAttachmentSanitizer.SanitizedImage.class);
        verify(activityService).comment(eq("req-1"), eq("Con foto"), image.capture(), any());
        assertNotNull(image.getValue());
        assertEquals("image/png", image.getValue().contentType());
    }

    @Test
    void multipartSinFotoPasaNull() throws Exception {
        when(activityService.comment(eq("req-1"), eq("Solo texto"), isNull(), any())).thenReturn(VIEW);

        mockMvc.perform(multipart(COMMENTS)
                        .file(comment("Solo texto"))
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isCreated());
    }

    @Test
    void unArchivoQueNoEsImagenResponde400SinLlegarAlServicio() throws Exception {
        mockMvc.perform(multipart(COMMENTS)
                        .file(comment("Texto"))
                        .file(new MockMultipartFile("attachment", "foto.png", "image/png", "no soy png".getBytes()))
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isBadRequest());

        verify(activityService, never()).comment(anyString(), any(), any(), any());
    }

    @Test
    void unaImagenDeMasDe5MbResponde413SinLlegarAlServicio() throws Exception {
        byte[] big = new byte[(int) ImageAttachmentSanitizer.MAX_FILE_BYTES + 1];

        mockMvc.perform(multipart(COMMENTS)
                        .file(comment("Texto"))
                        .file(new MockMultipartFile("attachment", "foto.png", "image/png", big))
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isPayloadTooLarge());

        verify(activityService, never()).comment(anyString(), any(), any(), any());
    }

    @Test
    void elConflictoDelServicioLlegaComo409ConSuMensaje() throws Exception {
        when(activityService.comment(any(), any(), any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "El tramite ya tiene el maximo de 20 comentarios"));

        mockMvc.perform(post(COMMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .content("{\"text\":\"Uno mas\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El tramite ya tiene el maximo de 20 comentarios"));
    }

    @Test
    void laDescargaUsaElTramiteYElComentarioConHeadersPrivados() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(activityService.getCommentAttachment(eq("req-1"), eq("act-1"), any()))
                .thenReturn(new RequestAttachmentContent(bytes, "image/png", "evidencia.png"));

        mockMvc.perform(get(COMMENTS + "/act-1/attachment")
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(activityService).getCommentAttachment(eq("req-1"), eq("act-1"), caller.capture());
        assertEquals("oid-1", caller.getValue().userId());
    }

    private static MockMultipartFile comment(String text) {
        return new MockMultipartFile("comment", "", MediaType.APPLICATION_JSON_VALUE,
                ("{\"text\":\"" + text + "\"}").getBytes());
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
