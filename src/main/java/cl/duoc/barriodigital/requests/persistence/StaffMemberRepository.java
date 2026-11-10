package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.StaffMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Personal municipal visto por requests. */
public interface StaffMemberRepository extends JpaRepository<StaffMember, String> {

    /** Personal con un rol, ordenado por nombre. */
    List<StaffMember> findByRoleOrderByNameAsc(String role);
}
