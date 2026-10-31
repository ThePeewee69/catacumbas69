package com.catacumbas69.pulsario.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.pulsario.dto.MessageDTO;
import com.catacumbas69.pulsario.service.AuditAction;
import com.catacumbas69.pulsario.service.AuditService;
import com.catacumbas69.pulsario.service.MessageService;
import com.catacumbas69.pulsario.service.PulsarAdminService;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PulsarController.class)
class PulsarControllerTest {

    private static final String TOPIC = "persistent://home/media/events";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PulsarAdminService admin;

    @MockitoBean
    private MessageService messages;

    @MockitoBean
    private AuditService audit;

    @Test
    void tenants_returnsList() throws Exception {
        when(admin.listTenants()).thenReturn(List.of("home", "public"));

        mvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("home"));
    }

    @Test
    void rejectsInvalidNames_beforeCallingPulsar() throws Exception {
        mvc.perform(get("/api/v1/namespaces/bad name"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItem("tenant")));
        verify(admin, never()).listNamespaces(any());
    }

    @Test
    void peek_rejectsCountAboveLimit() throws Exception {
        mvc.perform(get("/api/v1/topics/home/media/events/peek/sub").param("count", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("count")));
        verify(messages, never()).peek(any(), any(), anyInt());
    }

    @Test
    void peek_returnsBinaryFlag() throws Exception {
        when(messages.peek(TOPIC, "sub", 10)).thenReturn(List.of(
                new MessageDTO("1:0:-1", "AAE=", null, 0L, Map.of(), true)));

        mvc.perform(get("/api/v1/topics/home/media/events/peek/sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].binary").value(true));
    }

    @Test
    void produce_rejectsMissingPayload() throws Exception {
        mvc.perform(post("/api/v1/topics/home/media/events/produce")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("payload")));
    }

    @Test
    void produce_returnsMessageId_andAudits() throws Exception {
        when(messages.produce(TOPIC, "hi", "k", null)).thenReturn("5:1:-1");

        mvc.perform(post("/api/v1/topics/home/media/events/produce")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":\"hi\",\"key\":\"k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("5:1:-1"));
        verify(audit).recordMessage(any(), eq(AuditAction.PRODUCE), eq(TOPIC), eq("hi"), eq("k"), any());
    }

    @Test
    void skip_returns204() throws Exception {
        mvc.perform(post("/api/v1/topics/home/media/events/subscriptions/sub/skip/3"))
                .andExpect(status().isNoContent());
        verify(messages).skip(TOPIC, "sub", 3);
    }

    @Test
    void clearBacklog_returns204_andAudits() throws Exception {
        mvc.perform(post("/api/v1/topics/home/media/events/subscriptions/sub/clear-backlog"))
                .andExpect(status().isNoContent());
        verify(messages).clearBacklog(TOPIC, "sub");
        verify(audit).record(any(), eq(AuditAction.CLEAR_BACKLOG), eq(TOPIC), eq(Map.of("subscription", "sub")));
    }

    @Test
    void clearBacklog_rejectsInvalidSubscriptionName() throws Exception {
        mvc.perform(post("/api/v1/topics/home/media/events/subscriptions/bad name/clear-backlog"))
                .andExpect(status().isBadRequest());
        verify(messages, never()).clearBacklog(any(), any());
    }

    @Test
    void mapsPulsarNotFound_to404() throws Exception {
        when(admin.getTopicStats(TOPIC)).thenThrow(mock(PulsarAdminException.NotFoundException.class));

        mvc.perform(get("/api/v1/topics/home/media/events/stats"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void mapsPulsarServerErrors_to502() throws Exception {
        PulsarAdminException failure = mock(PulsarAdminException.class);
        when(failure.getStatusCode()).thenReturn(500);
        when(admin.listTenants()).thenThrow(failure);

        mvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Pulsar unavailable"));
    }
}
