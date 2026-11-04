package com.catacumbas69.cataudit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.cataudit.model.AuditEvent;
import com.catacumbas69.cataudit.model.AuditValue;
import com.catacumbas69.cataudit.service.AuditPage;
import com.catacumbas69.cataudit.service.AuditQuery;
import com.catacumbas69.cataudit.service.AuditStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.catacumbas69.cataudit.config.CatauditProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditController.class)
@Import(AuditControllerTest.Properties.class)
class AuditControllerTest {

    /** Web slices do not bind {@code @ConfigurationProperties}; the controller needs the page limits. */
    @TestConfiguration
    @EnableConfigurationProperties(CatauditProperties.class)
    static class Properties {
    }

    private static final AuditEvent EVENT = new AuditEvent("e1", "/catacumbas69/catapulsar",
            "com.catacumbas69.catapulsar.topic.created.v1", "catapulsar", "topic", "created", "persistent://a/b/c",
            Instant.parse("2026-10-31T19:00:00Z"), "10.0.0.1", null,
            List.of(new AuditValue("source.topic", "persistent://a/b/c")), List.of(), Instant.EPOCH);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditStore store;

    @Test
    void search_passesFilters_andCapsThePageSize() throws Exception {
        when(store.find(any())).thenReturn(new AuditPage(List.of(EVENT), 2, 500, 1001));

        mvc.perform(get("/api/v1/audit").param("app", "catapulsar").param("entity", "topic")
                        .param("from", "2026-10-01T00:00:00Z").param("page", "2").param("size", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("com.catacumbas69.catapulsar.topic.created.v1"))
                .andExpect(jsonPath("$.items[0].action").value("created"))
                .andExpect(jsonPath("$.items[0].after['source.topic']").value("persistent://a/b/c"))
                .andExpect(jsonPath("$.total").value(1001));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(store).find(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().size()).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(query.getValue().app()).isEqualTo("catapulsar");
        org.assertj.core.api.Assertions.assertThat(query.getValue().from())
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        when(store.get("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/audit/nope")).andExpect(status().isNotFound());
    }

    @Test
    void facets_listValuesInUse() throws Exception {
        when(store.distinct("app")).thenReturn(List.of("catapulsar", "cataponte"));
        when(store.distinct("entity")).thenReturn(List.of("topic"));
        when(store.distinct("action")).thenReturn(List.of("created"));

        mvc.perform(get("/api/v1/audit/facets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps[1]").value("cataponte"));
    }

    @Test
    void search_returns503_whenMongoIsDown() throws Exception {
        when(store.find(any())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(get("/api/v1/audit")).andExpect(status().isServiceUnavailable());
    }
}
