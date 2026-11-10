package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.StaffMember;
import cl.duoc.barriodigital.requests.persistence.StaffMemberRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssigneeView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Registro del personal municipal y lista de funcionarios para asignar (contrato EP3-05, sección 5). */
@Service
public class StaffMemberService {

    /** Mismo mensaje del BFF y de EP3-07 para un rol que no puede usar el recurso. */
    static final String ROLE_NOT_ALLOWED = "No tienes el rol necesario para este recurso";

    private final StaffMemberRepository staff;
    private final Clock clock;

    public StaffMemberService(StaffMemberRepository staff, Clock clock) {
        this.staff = staff;
        this.clock = clock;
    }

    /**
     * Crea o actualiza la fila de quien llama, en su propia transacción y antes del caso de uso.
     * No captura excepciones: si dos primeras llamadas del mismo oid chocan en la PK, el error lo registra
     * el interceptor y la próxima llamada actualiza la fila. No reintenta.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(String oid, String name, String role) {
        Optional<StaffMember> found = staff.findById(oid);
        if (found.isPresent()) {
            found.get().seen(name, role, clock.instant());
        } else {
            staff.save(new StaffMember(oid, name, role, clock.instant()));
        }
    }

    /** Funcionarios a los que se puede asignar un trámite, por nombre. Solo para Funcionario y Admin. */
    @Transactional(readOnly = true)
    public List<AssigneeView> assignees(CallerContext caller) {
        if (!caller.isFuncionario() && !caller.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ROLE_NOT_ALLOWED);
        }
        return staff.findByRoleOrderByNameAsc(CallerContext.FUNCIONARIO).stream()
                .map(member -> new AssigneeView(member.getOid(), member.getName()))
                .toList();
    }
}
