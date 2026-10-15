package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MunicipalRequestRepository extends JpaRepository<MunicipalRequest, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MunicipalRequest r where r.id = :id")
    Optional<MunicipalRequest> findByIdForUpdate(@Param("id") String id);

    @Query("""
            SELECT r FROM MunicipalRequest r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:from IS NULL OR r.createdAt >= :from)
              AND (:to IS NULL OR r.createdAt <= :to)
              AND (:solicitanteId IS NULL OR r.solicitanteId = :solicitanteId)
            ORDER BY r.createdAt DESC
            """)
    List<MunicipalRequest> search(
            @Param("status") RequestStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("solicitanteId") String solicitanteId
    );
}
