package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.CivilTime;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.RequestCsvWriter;
import cl.duoc.barriodigital.requests.service.RequestExportService;
import cl.duoc.barriodigital.requests.service.RequestListQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Exportación CSV del listado (EP3-63). Acepta los filtros y el orden de GET /api/requests, sin page ni size.
 * Es una ruta literal, así que /api/requests/{id} no la captura.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestExportController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final RequestExportService exports;
    private final Clock clock;

    public RequestExportController(RequestExportService exports, Clock clock) {
        this.exports = exports;
        this.clock = clock;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String open,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String procedureType,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String sort,
            @RequestHeader(value = CallerContext.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CallerContext.HEADER_USER_ROLES, required = false) String roles
    ) {
        CallerContext caller = new CallerContext(userId, roles);
        RequestListQuery params = new RequestListQuery(
                status, from, to, assignee, open, q, procedureType, sector, null, null, sort);
        byte[] csv = RequestCsvWriter.write(exports.export(params, caller));
        String fileName = "tramites-" + LocalDate.now(clock.withZone(CivilTime.ZONE)) + ".csv";
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .contentLength(csv.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(csv);
    }
}
