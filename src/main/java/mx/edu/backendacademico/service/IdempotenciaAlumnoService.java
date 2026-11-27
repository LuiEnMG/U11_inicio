package mx.edu.backendacademico.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import mx.edu.backendacademico.dto.AlumnoMapper;
import mx.edu.backendacademico.dto.AlumnoResponse;
import mx.edu.backendacademico.dto.CrearAlumnoRequest;
import mx.edu.backendacademico.domain.Alumno;
import mx.edu.backendacademico.exception.IdempotenciaConflictoException;
import mx.edu.backendacademico.repository.jpa.IdempotencyKeyEntity;
import mx.edu.backendacademico.repository.jpa.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotenciaAlumnoService {

    public record ResultadoIdempotente(int statusCode, AlumnoResponse cuerpo, boolean repetida) {}

    private static final int MAX_ESPERAS = 25;
    private static final long ESPERA_MS = 40L;

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotenciaAlumnoService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public String calcularHuella(CrearAlumnoRequest request) {
        String canonico = String.join("|",
                normalizar(request.matricula()),
                normalizar(request.nombre()),
                normalizar(request.correo()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonico.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public ResultadoIdempotente ejecutar(
            String idempotencyKey,
            CrearAlumnoRequest request,
            Supplier<Alumno> creadorAlumno) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Alumno creado = creadorAlumno.get();
            return new ResultadoIdempotente(HttpStatus.CREATED.value(), AlumnoMapper.toResponse(creado), false);
        }

        String clave = idempotencyKey.trim();
        String hashActual = calcularHuella(request);

        Optional<IdempotencyKeyEntity> reservada = intentarReservar(clave, hashActual);
        if (reservada.isEmpty()) {
            return resolverExistente(clave, hashActual);
        }

        try {
            Alumno creado = creadorAlumno.get();
            AlumnoResponse response = AlumnoMapper.toResponse(creado);
            marcarCompletada(clave, HttpStatus.CREATED.value(), serializar(response));
            return new ResultadoIdempotente(HttpStatus.CREATED.value(), response, false);
        } catch (RuntimeException ex) {
            marcarFallida(clave);
            throw ex;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Optional<IdempotencyKeyEntity> intentarReservar(String clave, String hashActual) {
        try {
            IdempotencyKeyEntity guardada = repository.saveAndFlush(
                    new IdempotencyKeyEntity(clave, hashActual, "EN_PROCESO"));
            return Optional.of(guardada);
        } catch (DataIntegrityViolationException ex) {
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void marcarCompletada(String clave, int statusCode, String jsonBody) {
        repository.findByIdempotencyKey(clave).ifPresent(entity -> {
            entity.setStatusCode(statusCode);
            entity.setResponseBody(jsonBody);
            entity.setEstado("COMPLETADA");
            repository.saveAndFlush(entity);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void marcarFallida(String clave) {
        repository.findByIdempotencyKey(clave).ifPresent(entity -> {
            entity.setEstado("FALLIDA");
            repository.saveAndFlush(entity);
        });
    }

    private ResultadoIdempotente resolverExistente(String clave, String hashActual) {
        for (int intento = 0; intento < MAX_ESPERAS; intento++) {
            IdempotencyKeyEntity actual = repository.findByIdempotencyKey(clave)
                    .orElseThrow(() -> new IdempotenciaConflictoException("No se pudo recuperar la clave de idempotencia"));

            if (!actual.getRequestHash().equals(hashActual)) {
                throw new IdempotenciaConflictoException(
                        "La clave de idempotencia ya fue utilizada con un contenido distinto");
            }

            if ("COMPLETADA".equals(actual.getEstado()) && actual.getResponseBody() != null) {
                AlumnoResponse cuerpo = deserializar(actual.getResponseBody());
                int codigo = actual.getStatusCode() != null ? actual.getStatusCode() : HttpStatus.CREATED.value();
                return new ResultadoIdempotente(codigo, cuerpo, true);
            }

            if ("FALLIDA".equals(actual.getEstado())) {
                throw new IdempotenciaConflictoException(
                        "La operación asociada a la clave de idempotencia finalizó con error");
            }

            dormirBrevemente();
        }
        throw new IdempotenciaConflictoException(
                "La solicitud con esta clave de idempotencia sigue en proceso");
    }

    private void dormirBrevemente() {
        try {
            Thread.sleep(ESPERA_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupción mientras se esperaba la operación idempotente", e);
        }
    }

    private String normalizar(String valor) {
        return valor == null ? "" : valor.trim();
    }

    private String serializar(AlumnoResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error al serializar la respuesta de idempotencia", e);
        }
    }

    private AlumnoResponse deserializar(String json) {
        try {
            return objectMapper.readValue(json, AlumnoResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error al deserializar la respuesta de idempotencia", e);
        }
    }
}