package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Filtro por días civiles de Chile contra la consulta real en H2; no valida el entorno Oracle desplegado. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(RequestService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestListCivilDayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario", "Ana");

    @Autowired
    RequestService service;
    @Autowired
    MunicipalRequestRepository repository;

    @MockBean
    EventPublisher publisher;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // 6-nov en Chile (UTC-3) va de 03:00Z del 6 a 03:00Z del 7.
        save("antes", "2026-11-06T02:59:59.999999Z");          // 23:59:59,999999 del 5-nov en Chile
        save("inicio", "2026-11-06T03:00:00Z");                // 00:00 del 6-nov en Chile
        save("noche", "2026-11-07T01:00:00Z");                 // 22:00 del 6-nov en Chile, ya 7-nov en UTC
        save("ultimoSegundo", "2026-11-07T02:59:59.500000Z");  // 23:59:59,5 del 6-nov en Chile
        save("despues", "2026-11-07T03:00:00Z");               // 00:00 del 7-nov en Chile
    }

    @Test
    void unDiaIncluyeLaNocheYElUltimoSegundoEnHoraDeChile() {
        List<MunicipalRequest> found = service.list(null, DAY, DAY, FUNCIONARIO);

        assertEquals(List.of("ultimoSegundo", "noche", "inicio"), titles(found));
    }

    @Test
    void soloHastaIncluyeElDiaCompleto() {
        List<MunicipalRequest> found = service.list(null, null, DAY, FUNCIONARIO);

        assertEquals(List.of("ultimoSegundo", "noche", "inicio", "antes"), titles(found));
    }

    @Test
    void soloDesdeEmpiezaALaMedianocheDeChile() {
        List<MunicipalRequest> found = service.list(null, DAY.plusDays(1), null, FUNCIONARIO);

        assertEquals(List.of("despues"), titles(found));
    }

    private void save(String title, String createdAt) {
        MunicipalRequest request = new MunicipalRequest(title, "d", "bache", "dir", "vecino-1", "Vecino");
        ReflectionTestUtils.setField(request, "createdAt", Instant.parse(createdAt));
        repository.saveAndFlush(request);
    }

    private static List<String> titles(List<MunicipalRequest> requests) {
        return requests.stream().map(MunicipalRequest::getTitle).toList();
    }
}
