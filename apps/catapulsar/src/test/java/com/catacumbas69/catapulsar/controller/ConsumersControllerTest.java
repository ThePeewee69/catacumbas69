package com.catacumbas69.catapulsar.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catapulsar.dto.ConsumerStatusResponse;
import com.catacumbas69.catapulsar.service.ConsumerStatusService;
import com.catacumbas69.catapulsar.service.DeadLetterService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConsumersController.class)
class ConsumersControllerTest {

    private static final String DLQ = "persistent://catacumbas69/cataudit/audit-cataudit-DLQ";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ConsumerStatusService consumers;

    @MockitoBean
    private DeadLetterService deadLetters;

    @Test
    void listsTheConsumers() throws Exception {
        when(consumers.list()).thenReturn(List.of(new ConsumerStatusResponse("cataudit", "pod-1", "audit", "t", "s",
                "PAUSED_AUTO", "mongodb unavailable", "cataudit", "x", "y", "dlq", 10, null, false)));

        mvc.perform(get("/api/v1/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("PAUSED_AUTO"))
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    void command_isAccepted() throws Exception {
        mvc.perform(post("/api/v1/consumers/commands").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"cataudit\",\"instance\":\"*\",\"consumer\":\"audit\","
                                + "\"action\":\"resume\"}"))
                .andExpect(status().isAccepted());

        verify(consumers).send(eq("cataudit"), eq("*"), eq("audit"), eq("resume"), any());
    }

    @Test
    void command_refusesAnUnknownAction() throws Exception {
        mvc.perform(post("/api/v1/consumers/commands").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"cataudit\",\"instance\":\"*\",\"consumer\":\"audit\","
                                + "\"action\":\"kill\"}"))
                .andExpect(status().isBadRequest());

        verify(consumers, never()).send(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sendBack_answersTheTopicItWentTo() throws Exception {
        when(deadLetters.sendBack(eq(DLQ), eq("1:2:-1"), any())).thenReturn("persistent://catacumbas69/cataudit/audit");

        mvc.perform(post("/api/v1/dead-letters/send-back").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"" + DLQ + "\",\"messageId\":\"1:2:-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("persistent://catacumbas69/cataudit/audit"));
    }

    @Test
    void refusesATopicThatIsNotADeadLetterTopic() throws Exception {
        mvc.perform(post("/api/v1/dead-letters/discard-all").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"persistent://catacumbas69/cataudit/audit\"}"))
                .andExpect(status().isBadRequest());

        verify(deadLetters, never()).discardAll(anyString(), anyString());
    }
}
