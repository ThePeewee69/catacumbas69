package com.catacumbas69.catametricas.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catametricas.dto.ForcedCollectionResponse;
import com.catacumbas69.catametricas.service.ForcedCollection;
import com.catacumbas69.catametricas.service.InvalidQueryException;
import java.time.Instant;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CollectController.class)
class CollectControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ForcedCollection forced;

    @Test
    void collect_passesTheScopeAndTheForwardedClient() throws Exception {
        when(forced.collect("home", "media", "10.0.0.1")).thenReturn(new ForcedCollectionResponse("home/media",
                List.of("home/media"), 1, 0, List.of(), Instant.parse("2026-11-04T09:00:07Z"), 180));

        mvc.perform(post("/api/v1/collect").param("tenant", "home").param("namespace", "media")
                        .header("X-Forwarded-For", "10.0.0.1, 10.42.0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("home/media"))
                .andExpect(jsonPath("$.collected").value(1));
    }

    @Test
    void collect_returns400_forANamespaceWithoutTenant() throws Exception {
        when(forced.collect(null, "media", "127.0.0.1")).thenThrow(new InvalidQueryException("namespace needs tenant"));

        mvc.perform(post("/api/v1/collect").param("namespace", "media"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void collect_returns502_whenPulsarCannotBeRead() throws Exception {
        PulsarAdminException failure = mock(PulsarAdminException.class);
        when(failure.getMessage()).thenReturn("connection refused");
        when(forced.collect(null, null, "127.0.0.1")).thenThrow(failure);

        mvc.perform(post("/api/v1/collect"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Pulsar unavailable"));
    }
}
