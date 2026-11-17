package cl.duoc.barriodigital.requests.adapter.out.persistence;

import cl.duoc.barriodigital.requests.domain.UserNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, String> {
}
