package cl.duoc.barriodigital.requests.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escribe los errores con el cuerpo de Spring Boot (timestamp, status, error, message y path) y agrega code cuando
 * el error tiene uno (EP3-67). Los mensajes son los mismos que generaba Spring: el BFF y el frontend todavía los leen.
 */
@RestControllerAdvice
public class RequestsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RequestsExceptionHandler.class);
    /** El texto de Spring Boot cuando el error no trae mensaje. */
    private static final String NO_MESSAGE = "No message available";

    private final Clock clock;

    public RequestsExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Errores del dominio: con code si son RequestsException. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        if (ex.getStatusCode().is5xxServerError()) {
            log.error("Error interno en requests path={}", request.getRequestURI(), ex);
        }
        RequestsErrorCode code = ex instanceof RequestsException coded ? coded.code() : null;
        return body(status, ex.getReason(), request, code);
    }

    /** Body que no pasa @Valid, con el mismo mensaje que arma Spring Boot a partir del BindingResult. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        BindingResult result = ex.getBindingResult();
        String message = "Validation failed for object='" + result.getObjectName() + "'. Error count: "
                + result.getErrorCount();
        return body(HttpStatus.BAD_REQUEST.value(), message, request, RequestsErrorCode.REQUEST_BODY_INVALID);
    }

    /** Body ilegible, con el mensaje de la excepción como lo devolvía Spring Boot. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), request, RequestsErrorCode.REQUEST_BODY_INVALID);
    }

    private ResponseEntity<Map<String, Object>> body(
            int status, String message, HttpServletRequest request, RequestsErrorCode code
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Date, como Spring Boot, para que Jackson escriba el mismo formato.
        body.put("timestamp", Date.from(clock.instant()));
        body.put("status", status);
        body.put("error", errorName(status));
        body.put("message", message == null || message.isBlank() ? NO_MESSAGE : message);
        body.put("path", request.getRequestURI());
        if (code != null) {
            body.put("code", code.name());
        }
        return ResponseEntity.status(status).body(body);
    }

    private static String errorName(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
