package com.foody.delivery.shared.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.foody.delivery.auth.DatabaseBusyException;
import com.foody.delivery.auth.EmailAlreadyExistsException;
import com.foody.delivery.auth.InvalidCredentialsException;
import com.foody.delivery.order.InvalidOrderIdException;
import com.foody.delivery.order.OrderNotFoundException;
import com.foody.delivery.order.InvalidStatusTransitionException;
import com.foody.delivery.order.OrderVersionConflictException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({InvalidOrderIdException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleInvalidOrderId(HttpServletRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dados inválidos",
                "Informe um identificador inteiro positivo válido.", request);
        problem.setProperty("violations", List.of(new Violation("id", "Informe um identificador inteiro positivo válido.")));
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> handleOrderNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND", "Pedido não encontrado", "O pedido informado não foi encontrado.", request));
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidStatusTransition(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                "INVALID_STATUS_TRANSITION", "Mudança de status inválida",
                "A mudança de status solicitada não é permitida.", request));
    }

    @ExceptionHandler(OrderVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleOrderVersionConflict(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT,
                "ORDER_VERSION_CONFLICT", "Pedido alterado",
                "O pedido foi alterado. Recarregue os dados antes de confirmar uma nova alteração.", request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::message))
                .toList();
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Dados inválidos",
                "Revise os campos informados.",
                request);
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Dados inválidos",
                "O corpo da requisição é inválido ou contém campos não permitidos.",
                request);
        problem.setProperty("violations", bindingViolations(exception));
        return ResponseEntity.badRequest().body(problem);
    }

    private static List<Violation> bindingViolations(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                var field = new StringBuilder();
                for (var reference : mapping.getPath()) {
                    if (reference.getFieldName() != null) {
                        if (!field.isEmpty()) {
                            field.append('.');
                        }
                        field.append(reference.getFieldName());
                    } else if (reference.getIndex() >= 0) {
                        field.append('[').append(reference.getIndex()).append(']');
                    }
                }
                if (!field.isEmpty()) {
                    return List.of(new Violation(field.toString(), "Informe um valor válido para este campo."));
                }
            }
        }
        return List.of();
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> handlePersistenceFailure(HttpServletRequest request) {
        var problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Falha ao processar a solicitação",
                "Não foi possível concluir a operação.",
                request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleDuplicateEmail(
            EmailAlreadyExistsException exception,
            HttpServletRequest request) {
        var problem = problem(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                "E-mail já cadastrado",
                exception.getMessage(),
                request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DatabaseBusyException.class)
    ResponseEntity<ProblemDetail> handleDatabaseBusy(
            DatabaseBusyException exception,
            HttpServletRequest request) {
        var problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_BUSY",
                "Banco de dados ocupado",
                exception.getMessage(),
                request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(
            InvalidCredentialsException exception,
            HttpServletRequest request) {
        var problem = problem(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Credenciais inválidas",
                exception.getMessage(),
                request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    private record Violation(String field, String message) {
    }
}
