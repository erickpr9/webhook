package com.estudos.webhook.api.response;

import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Formato padrão de erro de todos os endpoints.
 *
 * @param timestamp instante do erro
 * @param status    código HTTP
 * @param error     descrição do código HTTP
 * @param message   mensagem para o cliente (nunca expõe detalhes internos)
 * @param path      caminho da requisição
 * @param details   detalhes de validação, quando houver
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                            List<String> details) {

    public static ErrorResponse of(Clock clock, HttpStatus status, String message, String path) {
        return of(clock, status, message, path, List.of());
    }

    public static ErrorResponse of(Clock clock, HttpStatus status, String message, String path, List<String> details) {
        return new ErrorResponse(clock.instant(), status.value(), status.getReasonPhrase(), message, path,
                List.copyOf(details));
    }
}
