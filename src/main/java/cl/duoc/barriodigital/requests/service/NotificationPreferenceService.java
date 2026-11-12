package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.StoredInstant;
import cl.duoc.barriodigital.requests.domain.UserNotificationPreference;
import cl.duoc.barriodigital.requests.persistence.UserNotificationPreferenceRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Preferencias de aviso por correo (EP3-65). Sin fila = correo habilitado.
 */
@Service
public class NotificationPreferenceService {

    private final UserNotificationPreferenceRepository repository;

    public NotificationPreferenceService(UserNotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    /**
     * Indica si el usuario recibe avisos email.*. Sin fila o con emailEnabled=true → true.
     */
    @Transactional(readOnly = true)
    public boolean isEmailEnabled(String userId) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        return repository.findById(userId.trim())
                .map(UserNotificationPreference::isEmailEnabled)
                .orElse(true);
    }

    /** Preferencia del caller. Sin fila → emailEnabled true. */
    @Transactional(readOnly = true)
    public UserNotificationPreference get(CallerContext caller) {
        String userId = requireUserId(caller);
        return repository.findById(userId)
                .orElseGet(() -> new UserNotificationPreference(userId, true, StoredInstant.now()));
    }

    /** Crea o actualiza la preferencia del caller. */
    @Transactional
    public UserNotificationPreference update(boolean emailEnabled, CallerContext caller) {
        String userId = requireUserId(caller);
        Instant now = StoredInstant.now();
        UserNotificationPreference row = repository.findById(userId)
                .orElseGet(() -> new UserNotificationPreference(userId, emailEnabled, now));
        row.setEmailEnabled(emailEnabled, now);
        return repository.save(row);
    }

    private static String requireUserId(CallerContext caller) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."
            );
        }
        return caller.userId().trim();
    }
}
