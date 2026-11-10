package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestCreationOrchestrationService;
import cl.duoc.barriodigital.requests.service.RequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Un rol por usuario en toda la API de requests, salvo /meta/** (contrato EP3-04, secciones 1 y 4). */
@WebMvcTest(RequestController.class)
class SingleRoleInterceptorTest {

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
    void sinRolResponde403AntesDelCasoDeUso() throws Exception {
        mockMvc.perform(get("/api/requests").header(CallerContext.HEADER_USER_ID, "u-1"))
                .andExpect(status().isForbidden())
                .andExpect(status().reason("No tienes un rol asignado en BarrioDigital"));

        verifyNoInteractions(requestService);
    }

    @Test
    void conMasDeUnRolResponde403AntesDelCasoDeUso() throws Exception {
        mockMvc.perform(get("/api/requests/id-1")
                        .header(CallerContext.HEADER_USER_ID, "u-1")
                        .header(CallerContext.HEADER_USER_ROLES, "Funcionario,Vecino"))
                .andExpect(status().isForbidden())
                .andExpect(status().reason("El usuario debe tener un solo rol"));

        verifyNoInteractions(requestService);
    }

    @Test
    void metaTransitionsSigueRespondiendoSinIdentidad() throws Exception {
        mockMvc.perform(get("/api/requests/meta/transitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.INGRESADO").isArray());
    }
}
