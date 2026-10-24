package ru.repaircalc.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.stream.Stream;
import ru.repaircalc.calculation.domain.CalculationException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                new ApiError("invalid_json", "Request body must contain valid JSON."),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .toList();
        return handleExceptionInternal(exception, validationError(fields),
                headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            log.error("Handler method returned an invalid result");
            return handleExceptionInternal(exception, internalError(),
                    headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        }
        List<String> fields = exception.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::parameterFields)
                .distinct()
                .sorted()
                .toList();
        return handleExceptionInternal(exception, validationError(fields),
                headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> handleDomainException(DomainException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ApiError(exception.code(), exception.getMessage(), exception.fields()));
    }

    @ExceptionHandler(CalculationException.class)
    ResponseEntity<ApiError> handleCalculationException(CalculationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ApiError(exception.code(), exception.getMessage(), exception.fields()));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiError> handleProductNotFound(ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("product_not_found", "The requested product was not found."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        // Exception messages can contain credentials or request data; keep default logs safe too.
        log.error("Unhandled request exception of type {}", exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(internalError());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(ServiceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Object error = body instanceof ApiError ? body : standardError(status);
        return super.handleExceptionInternal(exception, error, headers, status, request);
    }

    private static ApiError validationError(List<String> fields) {
        return new ApiError("validation_failed", "Request validation failed.", fields);
    }

    private static ApiError internalError() {
        return new ApiError("internal_error", "An internal server error occurred.");
    }

    private static ApiError standardError(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return internalError();
        }
        return switch (status.value()) {
            case 400 -> new ApiError("bad_request", "The request is invalid.");
            case 404 -> new ApiError("not_found", "The requested resource was not found.");
            case 405 -> new ApiError("method_not_allowed", "This HTTP method is not supported.");
            case 415 -> new ApiError("unsupported_media_type", "The request media type is not supported.");
            default -> new ApiError("request_error", "The request could not be processed.");
        };
    }

    private static Stream<String> parameterFields(ParameterValidationResult result) {
        if (result instanceof ParameterErrors errors && errors.hasFieldErrors()) {
            return errors.getFieldErrors().stream().map(FieldError::getField);
        }
        return Stream.of(parameterName(result.getMethodParameter()));
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return namedParameter(requestParam.name(), requestParam.value(), parameter);
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return namedParameter(pathVariable.name(), pathVariable.value(), parameter);
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return namedParameter(requestHeader.name(), requestHeader.value(), parameter);
        }
        return namedParameter("", "", parameter);
    }

    private static String namedParameter(String name, String value, MethodParameter parameter) {
        if (!name.isBlank()) {
            return name;
        }
        if (!value.isBlank()) {
            return value;
        }
        String parameterName = parameter.getParameterName();
        return parameterName == null ? "arg" + parameter.getParameterIndex() : parameterName;
    }
}
