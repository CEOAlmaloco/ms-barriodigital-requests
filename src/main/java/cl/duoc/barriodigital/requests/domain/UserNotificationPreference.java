package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Preferencias de aviso por correo de un usuario (EP3-65).
 * La clave es el X-User-Id. Si no hay fila, se asume email habilitado (opt-out).
 */
@Entity
@Table(name = "user_notification_prefs")
public class UserNotificationPreference {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /** Si es false, requests no publica comandos email.* a RabbitMQ para trámites de este usuario. */
    @Column(name = "email_enabled", nullable = false, columnDefinition = "NUMBER(1) DEFAULT 1")
    private boolean emailEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserNotificationPreference() {
        // JPA
    }

    public UserNotificationPreference(String userId, boolean emailEnabled, Instant updatedAt) {
        this.userId = userId;
        this.emailEnabled = emailEnabled;
        this.updatedAt = updatedAt;
    }

    public void setEmailEnabled(boolean emailEnabled, Instant updatedAt) {
        this.emailEnabled = emailEnabled;
        this.updatedAt = updatedAt;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
