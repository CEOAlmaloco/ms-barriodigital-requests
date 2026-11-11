package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.persistence.StaffMemberRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssigneeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** staff_member contra JPA real en H2; no valida el entorno Oracle desplegado. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StaffMemberService.class, ClockConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StaffMemberH2Test {

    @Autowired StaffMemberService service;
    @Autowired StaffMemberRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void touchCreaYDespuesActualizaLaMismaFila() {
        service.touch("oid-1", "Ana", "Funcionario");
        service.touch("oid-1", "Ana María", "Funcionario");

        assertEquals(1, repository.count());
        assertEquals("Ana María", repository.findById("oid-1").orElseThrow().getName());
    }

    @Test
    void laListaTraeSoloFuncionariosOrdenadosPorNombre() {
        service.touch("oid-c", "Carla", "Funcionario");
        service.touch("oid-a", "Ana", "Funcionario");
        service.touch("oid-adm", "Admin Uno", "Admin");
        service.touch("oid-aud", "Auditora", "Auditor");

        List<AssigneeView> list = service.assignees(new CallerContext("oid-adm", "Admin"));

        assertEquals(List.of(new AssigneeView("oid-a", "Ana"), new AssigneeView("oid-c", "Carla")), list);
    }
}
