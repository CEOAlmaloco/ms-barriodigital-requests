package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.UserNotificationPreference;
import jakarta.validation.constraints.NotNull;

/**
 * Preferencia de avisos por correo (EP3-65).
 * En PUT, emailEnabled es obligatorio. En GET, refleja la fila o el default (true).
 */
public record NotificationPreferenceDto(
        @NotNull(message = "emailEnabled es obligatorio") Boolean emailEnabled
) {

    public static NotificationPreferenceDto of(UserNotificationPreference preference) {
        return new NotificationPreferenceDto(preference.isEmailEnabled());
    }
}
