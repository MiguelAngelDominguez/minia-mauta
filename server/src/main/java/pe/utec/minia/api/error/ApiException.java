package pe.utec.minia.api.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final String codigo;
    private final HttpStatus status;

    public ApiException(String codigo, HttpStatus status, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
        this.status = status;
    }

    public String getCodigo() {
        return codigo;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String codigo, String mensaje) {
        return new ApiException(codigo, HttpStatus.BAD_REQUEST, mensaje);
    }

    public static ApiException notFound(String codigo, String mensaje) {
        return new ApiException(codigo, HttpStatus.NOT_FOUND, mensaje);
    }

    public static ApiException unauthorized(String codigo, String mensaje) {
        return new ApiException(codigo, HttpStatus.UNAUTHORIZED, mensaje);
    }

    public static ApiException serviceUnavailable(String codigo, String mensaje) {
        return new ApiException(codigo, HttpStatus.SERVICE_UNAVAILABLE, mensaje);
    }

    public static ApiException internal(String codigo, String mensaje) {
        return new ApiException(codigo, HttpStatus.INTERNAL_SERVER_ERROR, mensaje);
    }
}
