package mx.edu.backendacademico.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mx.edu.backendacademico.dto.CrearAlumnoRequest;
import mx.edu.backendacademico.repository.jpa.AlumnoSpringDataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlumnoIdempotenciaTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlumnoSpringDataRepository alumnoRepository;

    @Test
    void dosSolicitudesSimultaneasConMismaClaveCreanUnSoloAlumno() throws Exception {
        String clave = "idem-simultanea-001";
        var payload = new CrearAlumnoRequest("MAT-IDEM-1", "Grace Hopper", "grace@u.mx");
        String json = objectMapper.writeValueAsString(payload);

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch disparar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<MvcResult> tarea = () -> {
            listos.countDown();
            assertTrue(disparar.await(5, TimeUnit.SECONDS));
            return mockMvc.perform(post("/api/v1/alumnos")
                            .header("Idempotency-Key", clave)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn();
        };

        List<Future<MvcResult>> futuros = new ArrayList<>();
        futuros.add(pool.submit(tarea));
        futuros.add(pool.submit(tarea));

        assertTrue(listos.await(5, TimeUnit.SECONDS));
        disparar.countDown();

        MvcResult r1 = futuros.get(0).get(10, TimeUnit.SECONDS);
        MvcResult r2 = futuros.get(1).get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(201, r1.getResponse().getStatus());
        assertEquals(201, r2.getResponse().getStatus());

        JsonNode body1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        JsonNode body2 = objectMapper.readTree(r2.getResponse().getContentAsString());

        assertEquals(body1.get("id").asLong(), body2.get("id").asLong());
        assertEquals("MAT-IDEM-1", body1.get("matricula").asText());

        long totalConMatricula = alumnoRepository.findAll().stream()
                .filter(a -> "MAT-IDEM-1".equals(a.getMatricula()))
                .count();
        assertEquals(1, totalConMatricula);
    }

    @Test
    void mismaClaveConDistintoContenidoRechazaConConflicto() throws Exception {
        String clave = "idem-conflicto-001";
        var primero = new CrearAlumnoRequest("MAT-IDEM-2", "Alan Turing", "alan@u.mx");
        var distinto = new CrearAlumnoRequest("MAT-IDEM-3", "Alan M. Turing", "alan.m@u.mx");

        mockMvc.perform(post("/api/v1/alumnos")
                        .header("Idempotency-Key", clave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(primero)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/alumnos")
                        .header("Idempotency-Key", clave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(distinto)))
                .andExpect(status().isConflict());

        boolean existeSegundo = alumnoRepository.findAll().stream()
                .anyMatch(a -> "MAT-IDEM-3".equals(a.getMatricula()));
        assertFalse(existeSegundo);
    }
}