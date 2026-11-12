package cl.duoc.barriodigital.requests.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationPreferenceFilterTest {

    @Test
    void conCorreoHabilitadoNoFiltra() {
        assertEquals(
                List.of("email.admitted", "crew.ticket"),
                RequestService.filterEmailCommands(true, List.of("email.admitted", "crew.ticket")));
    }

    @Test
    void conCorreoDesactivadoOmiteSoloEmail() {
        assertEquals(
                List.of("crew.ticket", "certificate.resolved"),
                RequestService.filterEmailCommands(
                        false, List.of("email.visit", "crew.ticket", "email.resolved", "certificate.resolved")));
        assertEquals(List.of(), RequestService.filterEmailCommands(false, List.of("email.admitted")));
        assertEquals(
                List.of("certificate.receipt"),
                RequestService.filterEmailCommands(false, List.of("certificate.receipt")));
    }
}
