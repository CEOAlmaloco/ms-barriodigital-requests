package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.RequestActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Marcas del expediente. La API solo agrega y lee. No hay borrado expuesto.
 */
public interface RequestActivityRepository extends JpaRepository<RequestActivity, String> {

    List<RequestActivity> findByRequestIdOrderByOccurredAtAsc(String requestId);
}
