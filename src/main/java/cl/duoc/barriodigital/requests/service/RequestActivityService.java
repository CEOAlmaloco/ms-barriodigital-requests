package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.web.dto.ActividadView.ContactoView;
import cl.duoc.barriodigital.requests.web.dto.ActividadView.EtapaView;
import cl.duoc.barriodigital.requests.web.dto.ActividadView.EventoView;
import cl.duoc.barriodigital.requests.web.dto.ActividadView.TiemposView;
import cl.duoc.barriodigital.requests.web.dto.RegisterGestionDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lee y agrega marcas del expediente. Crear y cambiar el estado también escriben una marca,
 * en la misma transacción, desde {@link RequestService}.
 */
@Service
public class RequestActivityService {

    private static final Set<RequestStatus> MUNICIPAL = EnumSet.of(
            RequestStatus.ADMITIDO, RequestStatus.EN_GESTION, RequestStatus.EN_TERRENO);
    private static final Set<RequestStatus> TERMINAL = EnumSet.of(
            RequestStatus.RESUELTO, RequestStatus.RECHAZADO);

    static final List<String> LIMITES = List.of(
            "La hora la marca el servidor en UTC. La pantalla la muestra en la zona del navegador.",
            "Los segundos son de calendario. Todavía no hay horario hábil ni feriados.",
            "Una gestión la declara el funcionario. No prueba que el vecino haya atendido.",
            "Los trámites anteriores a este registro no reconstruyen marcas que no se guardaron."
    );

    private final MunicipalRequestRepository requests;
    private final RequestActivityRepository activity;

    public RequestActivityService(MunicipalRequestRepository requests, RequestActivityRepository activity) {
        this.requests = requests;
        this.activity = activity;
    }

    /** Registra una gestión interna. Si el guardado falla, la transacción no confirma nada. */
    @Transactional
    public ActividadView registerGestion(String requestId, RegisterGestionDto body, CallerContext caller) {
        requireUser(caller);
        if (!caller.isFuncionario()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un Funcionario puede registrar una gestión");
        }
        MunicipalRequest request = locked(requestId);
        String note = body.note() == null ? null : body.note().trim();
        activity.save(RequestActivity.gestion(
                request.getId(),
                Instant.now(),
                caller.userId().trim(),
                RequestService.snapshotName(caller.userName(), caller.userId()),
                body.channel(),
                body.result(),
                note,
                body.nextAttemptAt()
        ));
        return build(request, caller, true);
    }

    /** Historial visible para quien pide. El vecino no recibe gestiones ni el resumen de intentos. */
    @Transactional(readOnly = true)
    public ActividadView get(String requestId, CallerContext caller) {
        requireUser(caller);
        MunicipalRequest request = visible(requestId, caller);
        return build(request, caller, caller.isStaff());
    }

    private ActividadView build(MunicipalRequest request, CallerContext caller, boolean staff) {
        List<RequestActivity> all = activity.findByRequestIdOrderByOccurredAtAsc(request.getId());
        List<RequestActivity> visible = staff
                ? all
                : all.stream().filter(event -> event.getVisibility() == ActivityVisibility.PUBLICO).toList();
        return new ActividadView(
                request.getId(),
                visible.stream().map(RequestActivityService::toEvento).toList(),
                tiempos(request, all, staff, Instant.now()),
                staff ? contacto(all) : null,
                LIMITES
        );
    }

    static TiemposView tiempos(MunicipalRequest request, List<RequestActivity> all, boolean staff, Instant now) {
        List<EtapaView> etapas = etapas(all, request.getStatus(), now);
        Long municipal = etapas.isEmpty() ? null : etapas.stream()
                .filter(etapa -> MUNICIPAL.contains(etapa.estado()))
                .mapToLong(EtapaView::segundos)
                .sum();
        if (etapas.stream().noneMatch(etapa -> MUNICIPAL.contains(etapa.estado()))) {
            municipal = null;
        }

        Instant attention = null;
        for (RequestActivity event : all) {
            boolean counts = event.getKind() == ActivityKind.ESTADO
                    || (staff && event.getKind() == ActivityKind.GESTION);
            if (counts && (attention == null || event.getOccurredAt().isBefore(attention))) {
                attention = event.getOccurredAt();
            }
        }
        Long primera = attention == null ? null : Math.max(0, Duration.between(request.getCreatedAt(), attention).getSeconds());

        RequestActivity cierre = all.stream()
                .filter(event -> event.getKind() == ActivityKind.ESTADO && TERMINAL.contains(event.getToStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
        boolean abierto = !TERMINAL.contains(request.getStatus());
        Long desdeIngreso = abierto ? Math.max(0, Duration.between(request.getCreatedAt(), now).getSeconds()) : null;
        Long resolucion = !abierto && cierre != null
                ? Math.max(0, Duration.between(request.getCreatedAt(), cierre.getOccurredAt()).getSeconds())
                : null;

        List<String> noCalculado = new ArrayList<>();
        noCalculado.add("Tiempo de asignación: el expediente todavía no tiene responsable.");
        noCalculado.add("Espera del vecino y espera de otra unidad: esos estados no existen.");
        noCalculado.add("Fuera de plazo: el tipo de trámite no tiene un plazo configurado.");
        if (!abierto && cierre == null) {
            noCalculado.add("Tiempo total: el trámite ya está cerrado y no hay marca del cierre.");
        }
        return new TiemposView(primera, municipal, desdeIngreso, resolucion, etapas, List.copyOf(noCalculado));
    }

    private static List<EtapaView> etapas(List<RequestActivity> all, RequestStatus current, Instant now) {
        List<RequestActivity> openings = all.stream()
                .filter(event -> event.getKind() == ActivityKind.CREADO || event.getKind() == ActivityKind.ESTADO)
                .toList();
        List<EtapaView> etapas = new ArrayList<>();
        for (int i = 0; i < openings.size(); i++) {
            RequestActivity event = openings.get(i);
            RequestStatus estado = event.getKind() == ActivityKind.CREADO
                    ? RequestStatus.INGRESADO
                    : event.getToStatus();
            if (estado == null) {
                continue;
            }
            Instant desde = event.getOccurredAt();
            Instant hasta;
            if (i + 1 < openings.size()) {
                hasta = openings.get(i + 1).getOccurredAt();
            } else if (TERMINAL.contains(current)) {
                hasta = desde;
            } else {
                hasta = now;
            }
            long segundos = Math.max(0, Duration.between(desde, hasta).getSeconds());
            etapas.add(new EtapaView(estado, desde, hasta, segundos));
        }
        return etapas;
    }

    private static ContactoView contacto(List<RequestActivity> all) {
        List<RequestActivity> gestiones = all.stream()
                .filter(event -> event.getKind() == ActivityKind.GESTION)
                .toList();
        if (gestiones.isEmpty()) {
            return new ContactoView(0, null, null, null);
        }
        RequestActivity last = gestiones.get(gestiones.size() - 1);
        String accion = last.getNextAttemptAt() == null ? null : "Reintentar contacto";
        return new ContactoView(gestiones.size(), last.getOccurredAt(), last.getNextAttemptAt(), accion);
    }

    private static EventoView toEvento(RequestActivity event) {
        return new EventoView(
                event.getId(),
                event.getOccurredAt(),
                event.getKind(),
                event.getVisibility(),
                event.getActorId(),
                event.getActorName(),
                event.getSummary(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getReason(),
                event.getChannel(),
                event.getResult(),
                event.getNote(),
                event.getNextAttemptAt()
        );
    }

    private MunicipalRequest locked(String requestId) {
        return requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
    }

    private MunicipalRequest visible(String requestId, CallerContext caller) {
        MunicipalRequest found = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        if (caller.mustFilterOwnRequests() && !caller.userId().trim().equals(found.getSolicitanteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado");
        }
        return found;
    }

    private static void requireUser(CallerContext caller) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."
            );
        }
    }
}
