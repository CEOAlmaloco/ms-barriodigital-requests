package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.domain.RequestTransitions;
import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import cl.duoc.barriodigital.requests.service.RequestActivityService;
import cl.duoc.barriodigital.requests.service.RequestAttachmentContent;
import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.web.dto.ActividadView;
import cl.duoc.barriodigital.requests.web.dto.ChangeStatusDto;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import cl.duoc.barriodigital.requests.web.dto.RegisterGestionDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API HTTP de los trámites.
 * La identidad llega en headers del BFF, no en el body.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;
    private final RequestActivityService activityService;
    private final ImageAttachmentSanitizer imageSanitizer;

    /** Recibe el servicio del trámite y el del historial. */
    public RequestController(
            RequestService requestService,
            RequestActivityService activityService,
            ImageAttachmentSanitizer imageSanitizer
    ) {
        this.requestService = requestService;
        this.activityService = activityService;
        this.imageSanitizer = imageSanitizer;
    }

    /** Crea un trámite con el body y la identidad de los headers. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MunicipalRequest> create(
            @Valid @RequestBody CreateRequestDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName,
            @RequestHeader(value = CallerContext.HEADER_USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_LABEL, required = false) String procedureLabel
    ) {
        MunicipalRequest created = requestService.create(
                body,
                new CallerContext(userId, roles, userName, userEmail, procedureLabel)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Crea un trámite con una sola imagen opcional; el archivo se valida y normaliza en el servidor. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MunicipalRequest> createWithAttachment(
            @Valid @RequestPart("request") CreateRequestDto body,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName,
            @RequestHeader(value = CallerContext.HEADER_USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = CallerContext.HEADER_PROCEDURE_LABEL, required = false) String procedureLabel
    ) {
        var image = attachment == null ? null : imageSanitizer.sanitize(attachment);
        MunicipalRequest created = requestService.create(
                body,
                new CallerContext(userId, roles, userName, userEmail, procedureLabel),
                image
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Descarga privada: identidad y acceso al trámite se validan antes de devolver los bytes. */
    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> getAttachment(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        RequestAttachmentContent attachment = requestService.getAttachment(id, new CallerContext(userId, roles));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .contentLength(attachment.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(attachment.content());
    }

    /** Cambia el estado. El rol llega en el header. */
    @PutMapping("/{id}/status")
    public MunicipalRequest updateStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeStatusDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        return requestService.changeStatus(
                id,
                body.status(),
                body.rejectionReason(),
                new CallerContext(userId, roles, userName)
        );
    }

    /** Historial, gestiones visibles según el rol y los tiempos que sí se pueden calcular. */
    @GetMapping("/{id}/actividad")
    public ActividadView actividad(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return activityService.get(id, new CallerContext(userId, roles));
    }

    /** Registra una gestión declarada por el funcionario. No confirma que el vecino haya atendido. */
    @PostMapping("/{id}/gestiones")
    public ResponseEntity<ActividadView> registrarGestion(
            @PathVariable String id,
            @Valid @RequestBody RegisterGestionDto body,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = CallerContext.HEADER_USER_NAME, required = false) String userName
    ) {
        ActividadView view = activityService.registerGestion(id, body, new CallerContext(userId, roles, userName));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** Devuelve un trámite. Quien no es personal solo obtiene el suyo. */
    @GetMapping("/{id}")
    public MunicipalRequest getById(
            @PathVariable String id,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.getById(id, new CallerContext(userId, roles));
    }

    /** Lista trámites. Las fechas from y to llegan como día, por ejemplo 2026-10-25. */
    @GetMapping
    public List<MunicipalRequest> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        return requestService.list(status, from, to, new CallerContext(userId, roles));
    }
    
    /**
     * Lista fija de tipos de trámite. La fuente vigente es el servicio catalog.
     * Sigue respondiendo para no romper a quien todavía la pide, y va antes de /{id}.
     *
     * @deprecated la fuente de verdad de los tipos de trámite es GET /api/catalog/procedures.
     */
    @Deprecated
    @GetMapping("/meta/procedure-types")
    public Map<String, String> procedureTypes() {
        return ProcedureTypes.asMap();
    }

    /** Devuelve los cambios de estado permitidos para que el cliente no copie la máquina de estados. */
    @GetMapping("/meta/transitions")
    public Map<String, List<String>> transitions() {
        return RequestTransitions.asNamesMap();
    }
}
