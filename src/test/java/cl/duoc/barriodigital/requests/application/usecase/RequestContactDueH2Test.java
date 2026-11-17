package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.TestListQueries;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.adapter.out.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.adapter.out.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.adapter.out.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.adapter.in.web.CallerContext;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.RequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Filtro contactDue contra la consulta real en H2 (EP3-66): última gestión por orden de registro, día civil de
 * Chile, trámites cerrados, filtros combinados y exportación. No valida el entorno Oracle desplegado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({RequestService.class, RequestActivityService.class, RequestExportService.class, FolioCounterService.class,
        DomainEventRecorder.class, NotificationPreferenceService.class, RequestContactDueH2Test.FixedClock.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestContactDueH2Test {

    /** 14-nov 12:00 en Chile (UTC-3): hoy termina en 2026-11-15T03:00Z. */
    private static final Instant NOON = Instant.parse("2026-11-14T15:00:00Z");
    private static final Instant TOMORROW = Instant.parse("2026-11-15T03:00:00Z");
    private static final Instant BASE = Instant.parse("2026-11-10T15:00:00Z");
    private static final String LOW_ID = "00000000-0000-4000-8000-000000000001";
    private static final String HIGH_ID = "00000000-0000-4000-8000-000000000002";
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario", "Ana");
    private static final CallerContext AUDITOR = new CallerContext("aud-1", "Auditor", "Control");

    @TestConfiguration
    static class FixedClock {
        static Instant now = NOON;

        @Bean
        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return Clock.fixed(now, zone);
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };
        }
    }

    @Autowired RequestService service;
    @Autowired RequestActivityService activityService;
    @Autowired RequestExportService exports;
    @Autowired MunicipalRequestRepository repository;
    @Autowired RequestActivityRepository activity;
    @MockBean EventPublisher publisher;

    private int nextFolio;

    @BeforeEach
    void clean() {
        activity.deleteAll();
        repository.deleteAll();
        FixedClock.now = NOON;
        nextFolio = 0;
    }

    @Test
    void entranLosVencidosYLosDeHoyHastaElUltimoInstanteEnChile() {
        gestion(save("vencido", 1, RequestStatus.EN_GESTION), 0, NOON.minusSeconds(86_400));
        gestion(save("hoy", 2, RequestStatus.EN_GESTION), 0, TOMORROW.minusNanos(1_000));
        gestion(save("manana", 3, RequestStatus.EN_GESTION), 0, TOMORROW);
        gestion(save("sinFecha", 4, RequestStatus.EN_GESTION), 0, null);
        save("sinGestiones", 5, RequestStatus.EN_GESTION);

        assertEquals(List.of("hoy", "vencido"), titles(due(query(), FUNCIONARIO)));
    }

    @Test
    void unaGestionPosteriorSinFechaAnulaElRecordatorio() {
        MunicipalRequest request = save("contestado", 1, RequestStatus.EN_GESTION);
        gestion(request, 0, NOON.minusSeconds(3_600));
        gestion(request, 1, null);

        assertEquals(List.of(), titles(due(query(), FUNCIONARIO)));
        assertNull(activityService.get(request.getId(), FUNCIONARIO).contacto().siguienteIntento());
    }

    @ParameterizedTest
    @CsvSource({LOW_ID + "," + HIGH_ID, HIGH_ID + "," + LOW_ID})
    void enUnEmpateDeInstantePrevaleceLaSegundaGestionSinImportarSusId(String firstId, String secondId) {
        MunicipalRequest request = save("contestado", 1, RequestStatus.EN_GESTION);
        gestion(request, 0, NOON.minusSeconds(3_600), firstId);
        gestion(request, 0, null, secondId);

        assertEquals(List.of(), titles(due(query(), FUNCIONARIO)));
        ActividadView detail = activityService.get(request.getId(), FUNCIONARIO);
        assertNull(detail.contacto().siguienteIntento());
        assertEquals(secondId, detail.eventos().get(detail.eventos().size() - 1).id());
    }

    @ParameterizedTest
    @CsvSource({LOW_ID + "," + HIGH_ID, HIGH_ID + "," + LOW_ID})
    void enUnEmpateDeInstanteLaSegundaGestionDejaSuRecordatorioSinImportarSusId(String firstId, String secondId) {
        MunicipalRequest request = save("pendiente", 1, RequestStatus.EN_GESTION);
        Instant retry = NOON.minusSeconds(3_600);
        gestion(request, 0, null, firstId);
        gestion(request, 0, retry, secondId);

        assertEquals(List.of("pendiente"), titles(due(query(), FUNCIONARIO)));
        assertEquals(retry, activityService.get(request.getId(), FUNCIONARIO).contacto().siguienteIntento());
    }

    @Test
    void conElRelojHaciaAtrasPrevaleceLaGestionRegistradaDespues() {
        MunicipalRequest anulado = save("anulado", 1, RequestStatus.EN_GESTION);
        gestion(anulado, 60, NOON.minusSeconds(3_600));
        gestion(anulado, 0, null);
        MunicipalRequest pendiente = save("pendiente", 2, RequestStatus.EN_GESTION);
        Instant retry = NOON.minusSeconds(3_600);
        gestion(pendiente, 60, null);
        gestion(pendiente, 0, retry);

        assertEquals(List.of("pendiente"), titles(due(query(), FUNCIONARIO)));
        ActividadView.ContactoView contacto = activityService.get(anulado.getId(), FUNCIONARIO).contacto();
        assertNull(contacto.siguienteIntento());
        assertEquals(BASE, contacto.ultimoIntento());
        assertEquals(retry, activityService.get(pendiente.getId(), FUNCIONARIO).contacto().siguienteIntento());
    }

    @Test
    void otrasMarcasPosterioresNoAnulanElRecordatorio() {
        MunicipalRequest request = save("conCambioDeEstado", 1, RequestStatus.EN_TERRENO);
        gestion(request, 0, NOON.minusSeconds(3_600));
        activity.saveAndFlush(RequestActivity.statusChanged(request.getId(), BASE.plusSeconds(60), "func-1", "Ana",
                "Funcionario", RequestStatus.EN_GESTION, RequestStatus.EN_TERRENO, null, null));

        assertEquals(List.of("conCambioDeEstado"), titles(due(query(), FUNCIONARIO)));
    }

    @Test
    void unTramiteCerradoNoTieneContactoPendiente() {
        gestion(save("resuelto", 1, RequestStatus.RESUELTO), 0, NOON.minusSeconds(3_600));
        gestion(save("rechazado", 2, RequestStatus.RECHAZADO), 0, NOON.minusSeconds(3_600));

        assertEquals(List.of(), titles(due(query(), FUNCIONARIO)));
        assertEquals(List.of(), titles(due(query().open("false"), FUNCIONARIO)));
    }

    @Test
    void seCombinaConAssigneeYLaPaginaCuentaSoloLosPendientes() {
        gestion(assign(save("mio1", 1, RequestStatus.EN_GESTION)), 0, NOON.minusSeconds(60));
        gestion(assign(save("mio2", 2, RequestStatus.EN_GESTION)), 0, NOON.minusSeconds(60));
        gestion(assign(save("mioSinPendiente", 3, RequestStatus.EN_GESTION)), 0, TOMORROW);
        gestion(save("sinResponsable", 4, RequestStatus.EN_GESTION), 0, NOON.minusSeconds(60));

        Page<MunicipalRequest> page = due(query().assignee("me").size(1), FUNCIONARIO);

        assertEquals(List.of("mio2"), titles(page));
        assertEquals(2, page.getTotalElements());
        assertEquals(List.of("sinResponsable"), titles(due(query().assignee("none"), FUNCIONARIO)));
    }

    @Test
    void elDiaDelCambioDeHoraTerminaEnLaMedianocheCivil() {
        // El 6-sep-2026 Chile pasa de UTC-4 a UTC-3 a las 00:00, que salta a la 01:00 (04:00Z).
        FixedClock.now = Instant.parse("2026-09-05T15:00:00Z");
        gestion(save("ultimoSegundo", 1, RequestStatus.EN_GESTION), 0, Instant.parse("2026-09-06T03:59:59Z"));
        gestion(save("diaSiguiente", 2, RequestStatus.EN_GESTION), 0, Instant.parse("2026-09-06T04:00:00Z"));

        assertEquals(List.of("ultimoSegundo"), titles(due(query(), FUNCIONARIO)));
    }

    @Test
    void laExportacionEntregaLosMismosTramitesQueElListado() {
        gestion(save("vencido", 1, RequestStatus.EN_GESTION), 0, NOON.minusSeconds(60));
        gestion(save("manana", 2, RequestStatus.EN_GESTION), 0, TOMORROW);

        List<RequestView> rows = exports.export(query().contactDue("true").build(), AUDITOR);

        assertEquals(List.of("vencido"), rows.stream().map(RequestView::title).toList());
        assertEquals(List.of("vencido"), titles(due(query(), AUDITOR)));
    }

    private Page<MunicipalRequest> due(TestListQueries filter, CallerContext caller) {
        return service.list(filter.contactDue("true").build(), caller);
    }

    private MunicipalRequest save(String title, int minute, RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest(title, "d", "bache", "dir", "vecino-1", "Vecino");
        ReflectionTestUtils.setField(request, "createdAt", BASE.plusSeconds(60L * minute));
        request.assignFolio(Folio.format(2097, ++nextFolio));
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private MunicipalRequest assign(MunicipalRequest request) {
        request.assignTo("func-1", "Ana");
        return repository.saveAndFlush(request);
    }

    private void gestion(MunicipalRequest request, int second, Instant nextAttemptAt) {
        gestion(request, second, nextAttemptAt, null);
    }

    /** Gestión en BASE + second, registrada en el orden de la llamada; con id para fijar su UUID. */
    private void gestion(MunicipalRequest request, int second, Instant nextAttemptAt, String id) {
        RequestActivity row = RequestActivity.gestion(request.getId(), BASE.plusSeconds(second), "func-1", "Ana",
                "Funcionario", ContactChannel.LLAMADA, ContactResult.NO_CONTESTA, null, nextAttemptAt, null);
        if (id != null) {
            ReflectionTestUtils.setField(row, "id", id);
        }
        activity.saveAndFlush(row);
    }

    private static List<String> titles(Page<MunicipalRequest> page) {
        return page.getContent().stream().map(MunicipalRequest::getTitle).toList();
    }
}
