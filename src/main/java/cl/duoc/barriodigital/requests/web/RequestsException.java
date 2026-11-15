package cl.duoc.barriodigital.requests.web;

import org.springframework.web.server.ResponseStatusException;

/** Error con código estable. Hereda de ResponseStatusException para conservar el status y el mensaje. */
public class RequestsException extends ResponseStatusException {

    private final RequestsErrorCode code;

    /** Error con el mensaje fijo del código. */
    public RequestsException(RequestsErrorCode code) {
        this(code, code.message());
    }

    /** Error con un mensaje armado para el caso, como el de una transición no permitida. */
    public RequestsException(RequestsErrorCode code, String message) {
        super(code.status(), message);
        this.code = code;
    }

    public RequestsErrorCode code() {
        return code;
    }
}
