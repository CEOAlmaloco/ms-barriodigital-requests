package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.FolioCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Contador de folios por año. Solo JPA, para que H2 pruebe la misma lógica que corre en Oracle. */
public interface FolioCounterRepository extends JpaRepository<FolioCounter, Integer> {

    /** Busca la fila del año y la bloquea en escritura hasta que termine la transacción del alta. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FolioCounter c where c.folioYear = :year")
    Optional<FolioCounter> findByIdForUpdate(@Param("year") int year);
}
