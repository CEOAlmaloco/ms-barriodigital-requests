package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.StaffMember;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.persistence.StaffMemberRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssignRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Toma, asignación y reasignación, con la tabla de errores de EP3-05, sección 4. */
class RequestAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
    private static final CallerContext ANA = new CallerContext("oid-ana", "Funcionario", "Ana%20Mar%C3%ADa");
    private static final CallerContext BRUNO = new CallerContext("oid-bruno", "Funcionario", "Bruno");
    private static final CallerContext ADMIN = new CallerContext("oid-admin", "Admin", "Admin%20Uno");

    private final MunicipalRequestRepository requests = mock(MunicipalRequestRepository.class);
    private final RequestActivityRepository activity = mock(RequestActivityRepository.class);
    private final StaffMemberRepository staff = mock(StaffMemberRepository.class);
    private final RequestAssignmentService service = new RequestAssignmentService(requests, activity, staff);

    private MunicipalRequest request;

    @BeforeEach
    void setUp() {
        request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1", "Vecino", null, null, NOW);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(any(MunicipalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(staff.findById("oid-ana")).thenReturn(Optional.of(new StaffMember("oid-ana", "Ana María", "Funcionario", NOW)));
        when(staff.findById("oid-bruno")).thenReturn(Optional.of(new StaffMember("oid-bruno", "Bruno", "Funcionario", NOW)));
        when(staff.findById("oid-admin")).thenReturn(Optional.of(new StaffMember("oid-admin", "Admin Uno", "Admin", NOW)));
    }

    // ---- Tomar ----

    @Test
    void elFuncionarioTomaUnTramiteSinResponsableYQuedaElHitoPublico() {
        MunicipalRequest taken = service.take(request.getId(), ANA);

        assertEquals("oid-ana", taken.getAssigneeId());
        assertEquals("Ana María", taken.getAssigneeName());
        List<RequestActivity> marks = savedMarks(2);
        RequestActivity internal = marks.get(0);
        assertEquals(ActivityKind.ASIGNACION, internal.getKind());
        assertEquals(ActivityVisibility.INTERNO, internal.getVisibility());
        assertEquals("Tomó el trámite", internal.getSummary());
        assertEquals("oid-ana", internal.getActorId());
        assertEquals("oid-ana", internal.getAssigneeId());
        assertNull(internal.getPreviousAssigneeId());
        assertNull(internal.getReason());
        RequestActivity milestone = marks.get(1);
        assertEquals(ActivityVisibility.PUBLICO, milestone.getVisibility());
        assertEquals("Tu solicitud fue asignada para su atención", milestone.getSummary());
        assertNull(milestone.getActorId());
        assertNull(milestone.getActorName());
        assertEquals(internal.getOccurredAt(), milestone.getOccurredAt());
    }

    @Test
    void elAdminNoTomaTramites() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.take(request.getId(), ADMIN));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo un Funcionario puede tomar un tramite", error.getReason());
        verifyNoInteractions(activity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Auditor"})
    void elVecinoYElAuditorNoTomanNiAsignan(String role) {
        CallerContext caller = new CallerContext("u-1", role);

        ResponseStatusException take = assertThrows(ResponseStatusException.class,
                () -> service.take(request.getId(), caller));
        ResponseStatusException assign = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-ana", null), caller));

        assertEquals(403, take.getStatusCode().value());
        assertEquals("No tienes el rol necesario para este recurso", take.getReason());
        assertEquals("No tienes el rol necesario para este recurso", assign.getReason());
        verifyNoInteractions(activity);
    }

    @Test
    void tomarUnTramiteQueNoExisteResponde404() {
        when(requests.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());

        assertEquals(404, statusOf(() -> service.take("no-existe", ANA)));
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"RESUELTO", "RECHAZADO"})
    void noSeTomaNiSeAsignaUnTramiteCerrado(RequestStatus closed) {
        request.setStatus(closed);

        ResponseStatusException take = assertThrows(ResponseStatusException.class,
                () -> service.take(request.getId(), ANA));
        ResponseStatusException assign = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-ana", null), ADMIN));

        assertEquals(409, take.getStatusCode().value());
        assertEquals("No se puede asignar un tramite cerrado", take.getReason());
        assertEquals("No se puede asignar un tramite cerrado", assign.getReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"oid-ana", "oid-bruno"})
    void noSeTomaUnTramiteQueYaTieneResponsableNiElPropio(String current) {
        request.assignTo(current, "Alguien");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.take(request.getId(), ANA));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("El tramite ya tiene responsable", error.getReason());
    }

    // ---- Asignar y reasignar ----

    @Test
    void elAdminAsignaPorPrimeraVezIgnorandoElMotivoYConHito() {
        MunicipalRequest assigned = service.assign(request.getId(), new AssignRequestDto("oid-ana", "no aplica"), ADMIN);

        assertEquals("oid-ana", assigned.getAssigneeId());
        assertEquals("Ana María", assigned.getAssigneeName());
        List<RequestActivity> marks = savedMarks(2);
        assertEquals("Asignó a Ana María", marks.get(0).getSummary());
        assertEquals("oid-admin", marks.get(0).getActorId());
        assertEquals("Admin Uno", marks.get(0).getActorName());
        assertNull(marks.get(0).getReason());
        assertNull(marks.get(0).getInterventionReason());
        assertEquals(ActivityVisibility.PUBLICO, marks.get(1).getVisibility());
    }

    @Test
    void elResponsableReasignaConMotivoYSinHito() {
        request.assignTo("oid-ana", "Ana María");

        MunicipalRequest reassigned = service.assign(request.getId(),
                new AssignRequestDto(" oid-bruno ", "  Vacaciones  "), ANA);

        assertEquals("oid-bruno", reassigned.getAssigneeId());
        RequestActivity mark = savedMarks(1).get(0);
        assertEquals("Reasignó de Ana María a Bruno. Motivo: Vacaciones", mark.getSummary());
        assertEquals("oid-ana", mark.getPreviousAssigneeId());
        assertEquals("Ana María", mark.getPreviousAssigneeName());
        assertEquals("Vacaciones", mark.getReason());
        assertEquals(ActivityVisibility.INTERNO, mark.getVisibility());
    }

    @Test
    void elAdminReasignaCualquierTramite() {
        request.assignTo("oid-ana", "Ana María");

        service.assign(request.getId(), new AssignRequestDto("oid-bruno", "Carga de trabajo"), ADMIN);

        assertEquals("oid-bruno", request.getAssigneeId());
        savedMarks(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void sinAssigneeIdResponde400AntesDeBuscarElTramite(String assigneeId) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto(assigneeId, null), ADMIN));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("assigneeId es obligatorio", error.getReason());
        verify(requests, never()).findByIdForUpdate(any());
    }

    @Test
    void unMotivoDeMasDe500Responde400() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-ana", "x".repeat(501)), ADMIN));

        assertEquals("reason no puede superar 500 caracteres", error.getReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"oid-desconocido", "oid-admin"})
    void elDestinatarioTieneQueSerUnFuncionarioDeLaLista(String assigneeId) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto(assigneeId, null), ADMIN));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("El destinatario debe ser un Funcionario de la lista", error.getReason());
        verify(requests, never()).findByIdForUpdate(any());
    }

    @Test
    void asignarUnTramiteQueNoExisteResponde404() {
        when(requests.findByIdForUpdate("no-existe")).thenReturn(Optional.empty());

        assertEquals(404, statusOf(() -> service.assign("no-existe", new AssignRequestDto("oid-ana", null), ADMIN)));
    }

    @Test
    void elFuncionarioNoAsignaUnTramiteSinResponsable() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-bruno", "motivo"), ANA));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Un tramite sin responsable se toma, no se asigna", error.getReason());
    }

    @Test
    void elFuncionarioQueNoEsElResponsableNoReasigna() {
        request.assignTo("oid-ana", "Ana María");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-bruno", "motivo"), BRUNO));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("Solo el responsable del tramite puede reasignarlo", error.getReason());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void reasignarSinMotivoResponde400ConLaFilaBloqueada(String reason) {
        request.assignTo("oid-ana", "Ana María");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-bruno", reason), ADMIN));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("reason es obligatorio al reasignar", error.getReason());
        verify(requests).findByIdForUpdate(request.getId());
        verifyNoInteractions(activity);
    }

    @Test
    void reasignarAlMismoResponsableResponde409AunqueSeaElPropioFuncionario() {
        request.assignTo("oid-ana", "Ana María");

        ResponseStatusException admin = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-ana", "motivo"), ADMIN));
        ResponseStatusException self = assertThrows(ResponseStatusException.class,
                () -> service.assign(request.getId(), new AssignRequestDto("oid-ana", "motivo"), ANA));

        assertEquals(409, admin.getStatusCode().value());
        assertEquals("El tramite ya esta asignado a ese funcionario", admin.getReason());
        assertEquals("El tramite ya esta asignado a ese funcionario", self.getReason());
        verifyNoInteractions(activity);
    }

    private List<RequestActivity> savedMarks(int expected) {
        ArgumentCaptor<RequestActivity> marks = ArgumentCaptor.forClass(RequestActivity.class);
        verify(activity, times(expected)).save(marks.capture());
        return marks.getAllValues();
    }

    private static int statusOf(Runnable call) {
        return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value();
    }
}
