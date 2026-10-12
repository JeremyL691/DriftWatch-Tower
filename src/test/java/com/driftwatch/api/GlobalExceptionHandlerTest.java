package com.driftwatch.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void unknownStaticResourceIsNotFoundNotServerError() {
        ResponseEntity<?> response = handler.handleGeneral(new NoResourceFoundException(HttpMethod.GET, "dashboard/missing.js"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(response)).containsEntry("error", "not_found").containsKeys("message", "timestamp");
    }

    @Test
    void unacceptableMediaTypeIsNotAcceptableWithAnEmptyBody() {
        ResponseEntity<?> response = handler.handleGeneral(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_PLAIN)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void unsupportedMethodIsMethodNotAllowed() {
        ResponseEntity<?> response = handler.handleGeneral(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(response)).containsEntry("error", "method_not_allowed");
    }

    @Test
    void missingParameterIsBadRequest() {
        ResponseEntity<?> response = handler.handleGeneral(
                new MissingServletRequestParameterException("page", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response)).containsEntry("error", "bad_request");
    }

    @Test
    void unexpectedFailureStaysAnInternalError() {
        ResponseEntity<?> response = handler.handleGeneral(new IllegalStateException("secret detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response)).containsEntry("error", "internal_error")
                .containsEntry("message", "An unexpected error occurred");
    }

    @Test
    void responseStatusLabelsFollowTheStatus() {
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.CONFLICT, "conflict")).getBody())
                .containsEntry("error", "idempotency_conflict");
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "retry")).getBody())
                .containsEntry("error", "unconfirmed");
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "gone")).getBody())
                .containsEntry("error", "not_found");
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad")).getBody())
                .containsEntry("error", "bad_request");
    }
}
