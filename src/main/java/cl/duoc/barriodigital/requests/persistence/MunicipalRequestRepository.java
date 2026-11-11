package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Acceso a los trámites en Oracle.
 * Puede bloquear una fila para cambiar el estado sin que dos escrituras se pisen. El listado se arma con
 * {@link MunicipalRequestSpecifications}.
 */
public interface MunicipalRequestRepository
        extends JpaRepository<MunicipalRequest, String>, JpaSpecificationExecutor<MunicipalRequest> {

    /** Busca el trámite y lo bloquea en escritura hasta que termine la transacción. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MunicipalRequest r where r.id = :id")
    Optional<MunicipalRequest> findByIdForUpdate(@Param("id") String id);
}
