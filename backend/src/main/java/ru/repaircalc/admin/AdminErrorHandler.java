package ru.repaircalc.admin;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AdminProductController.class, AdminAuthController.class})
public class AdminErrorHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AdminException.class)
    ResponseEntity<Error> admin(AdminException exception, HttpServletResponse response) {
        if (exception.status() == 429) response.setHeader("Retry-After", "60");
        return ResponseEntity.status(exception.status()).body(error(exception, response));
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var servlet = (org.springframework.web.context.request.ServletWebRequest) request;
        return new ResponseEntity<>(new Error("invalid_request", "Проверьте параметры запроса.", servlet.getResponse().getHeader("X-Request-ID"), List.of()), headers, status);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception exception, HttpServletResponse response) {
        org.slf4j.LoggerFactory.getLogger(AdminErrorHandler.class).error("Administrative request failed: {}", exception.getClass().getName());
        return ResponseEntity.internalServerError().body(new Error("internal_error", "Внутренняя ошибка сервера.", response.getHeader("X-Request-ID"), List.of()));
    }
    static Error error(AdminException exception, HttpServletResponse response) {
        return new Error(exception.code(), exception.getMessage(), response.getHeader("X-Request-ID"), exception.details());
    }
    public record Error(String code, String message, String requestId, List<AdminException.Detail> details) { }
}
