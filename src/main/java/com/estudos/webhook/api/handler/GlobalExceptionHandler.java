package com.estudos.webhook.api.handler;

import com.estudos.webhook.api.filter.PayloadTooLargeException;
import com.estudos.webhook.api.request.InvalidWebhookRequestException;
import com.estudos.webhook.api.response.ErrorResponse;
import com.estudos.webhook.api.security.InvalidSignatureException;
import com.estudos.webhook.application.exception.EventPublishingException;
import com.estudos.webhook.domain.exception.BusinessException;
import com.estudos.webhook.domain.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.util.List;

/**
 * Converte exceções em respostas {@link ErrorResponse}.
 *
 * <p>Regras de log: 4xx de cliente em WARN, sem corpo nem assinatura (podem conter dados
 * sensíveis); 404 sem log (não é anomalia); 500 em ERROR com stack trace, e mensagem genérica
 * para o cliente, para não vazar detalhes internos.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidWebhookRequestException.class)
    ResponseEntity<ErrorResponse> handleInvalidWebhook(InvalidWebhookRequestException ex, HttpServletRequest request) {
        return warn(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.details(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return warn(HttpStatus.BAD_REQUEST, "Missing required header " + ex.getHeaderName(), List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return warn(HttpStatus.BAD_REQUEST, "Request body is invalid", details, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                     HttpServletRequest request) {
        return warn(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", List.of(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (ex.getCause() instanceof PayloadTooLargeException tooLarge) {
            return warn(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getMessage(), List.of(), request);
        }
        return warn(HttpStatus.BAD_REQUEST, "Request body is missing or unreadable", List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return warn(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return warn(HttpStatus.METHOD_NOT_ALLOWED, "Method " + ex.getMethod() + " is not supported", List.of(),
                request);
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ResponseEntity<ErrorResponse> handleInvalidSignature(InvalidSignatureException ex, HttpServletRequest request) {
        // O motivo exato fica só no log, para não ajudar quem tenta forjar assinaturas.
        log.warn("Webhook REJEITADO (401) em {} {}: assinatura inválida ({}). Nada foi gravado.",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.UNAUTHORIZED, "Invalid webhook signature", List.of(), request);
    }

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        String message = ex instanceof ResourceNotFoundException ? ex.getMessage() : "Resource not found";
        return respond(HttpStatus.NOT_FOUND, message, List.of(), request);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return warn(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(EventPublishingException.class)
    ResponseEntity<ErrorResponse> handlePipelineUnavailable(EventPublishingException ex, HttpServletRequest request) {
        // O caso de uso já registrou o motivo em ERROR. O Retry-After orienta o reenvio do provedor.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(ErrorResponse.of(clock, HttpStatus.SERVICE_UNAVAILABLE,
                        "Webhook pipeline is temporarily unavailable. Retry later.", request.getRequestURI(),
                        List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado (500) em {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", List.of(), request);
    }

    private ResponseEntity<ErrorResponse> warn(HttpStatus status, String message, List<String> details,
                                               HttpServletRequest request) {
        log.warn("Requisição rejeitada ({}) em {} {}: {} {}", status.value(), request.getMethod(),
                request.getRequestURI(), message, details.isEmpty() ? "" : details);
        return respond(status, message, details, request);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String message, List<String> details,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(clock, status, message, request.getRequestURI(), details));
    }
}
