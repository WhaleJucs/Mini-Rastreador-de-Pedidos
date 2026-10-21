package com.foody.delivery.shared.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import com.foody.delivery.auth.DatabaseBusyException;
import com.foody.delivery.auth.EmailAlreadyExistsException;
import com.foody.delivery.auth.InvalidCredentialsException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpServletRequest request) {
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Dados inválidos",
                "O corpo da requisição é inválido ou contém campos não permitidos.",
                request);
        problem.setProperty("violations", List.of());
        return ResponseEntity.badRequest().body(problem);
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
