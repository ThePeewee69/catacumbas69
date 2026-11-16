package com.catacumbas69.catapulsar.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void session_namesTheSignedInUser() throws Exception {
        mvc.perform(get("/api/v1/session").principal(() -> "peewee69"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("peewee69"))
                .andExpect(jsonPath("$.login").value(true));
    }

    @Test
    void session_withoutLogin_saysSo() throws Exception {
        mvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.login").value(false));
    }
}
