package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestController.class)
class RequestControllerCreateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequestService requestService;

    @MockBean
    private RequestActivityService activityService;

    @MockBean
    private ImageAttachmentSanitizer imageSanitizer;

    @Test
    void postGuardaElEmailDelHeaderYLoDevuelve() throws Exception {
        when(requestService.create(any(CreateRequestDto.class), any(CallerContext.class)))
                .thenAnswer(invocation -> {
                    CallerContext caller = invocation.getArgument(1);
                    return new MunicipalRequest(
                            "t", "d", "bache", "dir", caller.userId(), "Alex", caller.userEmail()
                    );
                });

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .header(CallerContext.HEADER_USER_EMAIL, "vecino@example.com")
                        .content("{\"description\":\"Hueco\",\"procedureType\":\"bache\",\"address\":\"Calle 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solicitanteEmail").value("vecino@example.com"));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(requestService).create(any(CreateRequestDto.class), caller.capture());
        assertEquals("vecino@example.com", caller.getValue().userEmail());
    }

    @Test
    void postSinHeaderDeEmailDejaNull() throws Exception {
        when(requestService.create(any(CreateRequestDto.class), any(CallerContext.class)))
                .thenAnswer(invocation -> {
                    CallerContext caller = invocation.getArgument(1);
                    return new MunicipalRequest(
                            "t", "d", "bache", "dir", caller.userId(), "Alex", caller.userEmail()
                    );
                });

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .content("{\"description\":\"Hueco\",\"procedureType\":\"bache\",\"address\":\"Calle 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solicitanteEmail").value(nullValue()));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(requestService).create(any(CreateRequestDto.class), caller.capture());
        assertNull(caller.getValue().userEmail());
    }

    @Test
    void postPasaElHeaderDeLabelAlServicioYDevuelveProcedureLabel() throws Exception {
        when(requestService.create(any(CreateRequestDto.class), any(CallerContext.class)))
                .thenReturn(new MunicipalRequest(
                        "Alumbrado público — Calle 1", "d", "alumbrado", "Calle 1", "oid-1", "Alex", null,
                        "Alumbrado público"
                ));

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .header(CallerContext.HEADER_PROCEDURE_LABEL, "Alumbrado%20p%C3%BAblico")
                        .content("{\"description\":\"Poste\",\"procedureType\":\"alumbrado\",\"address\":\"Calle 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.procedureLabel").value("Alumbrado público"))
                .andExpect(jsonPath("$.title").value("Alumbrado público — Calle 1"));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(requestService).create(any(CreateRequestDto.class), caller.capture());
        assertEquals("Alumbrado%20p%C3%BAblico", caller.getValue().procedureLabel());
    }

    @Test
    void postSinHeaderDeLabelDevuelveProcedureLabelNull() throws Exception {
        when(requestService.create(any(CreateRequestDto.class), any(CallerContext.class)))
                .thenReturn(new MunicipalRequest("t", "d", "bache", "dir", "oid-1", "Alex", null));

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino")
                        .content("{\"description\":\"Hueco\",\"procedureType\":\"bache\",\"address\":\"Calle 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.procedureLabel").value(nullValue()));

        ArgumentCaptor<CallerContext> caller = ArgumentCaptor.forClass(CallerContext.class);
        verify(requestService).create(any(CreateRequestDto.class), caller.capture());
        assertNull(caller.getValue().procedureLabel());
    }

    @Test
    void postMultipartValidaYGuardaUnaImagenNormalizada() throws Exception {
        var image = new ImageAttachmentSanitizer.SanitizedImage(
                new byte[]{1, 2, 3},
                "image/png",
                "evidencia.png"
        );
        when(imageSanitizer.sanitize(any())).thenReturn(image);
        MunicipalRequest saved = new MunicipalRequest("titulo", "descripcion", "bache", "Calle 1", "oid-1");
        saved.setHasAttachment(true);
        when(requestService.create(any(CreateRequestDto.class), any(CallerContext.class), eq(image)))
                .thenReturn(saved);

        MockMultipartFile request = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"description\":\"Hueco\",\"procedureType\":\"bache\",\"address\":\"Calle 1\"}".getBytes()
        );
        MockMultipartFile attachment = new MockMultipartFile(
                "attachment",
                "original.svg",
                "image/png",
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/requests")
                        .file(request)
                        .file(attachment)
                        .header(CallerContext.HEADER_USER_ID, "oid-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Vecino"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasAttachment").value(true));

        verify(imageSanitizer).sanitize(attachment);
        verify(requestService).create(any(CreateRequestDto.class), any(CallerContext.class), eq(image));
    }
}
