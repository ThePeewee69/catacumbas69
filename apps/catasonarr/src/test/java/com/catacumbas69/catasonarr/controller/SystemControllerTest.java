package com.catacumbas69.catasonarr.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@Import(SystemControllerTest.Beans.class)
class SystemControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void status_answersWithSonarrsFieldNames() throws Exception {
        mvc.perform(get("/api/v3/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("Catasonarr"))
                .andExpect(jsonPath("$.instanceName").value("Catasonarr"))
                .andExpect(jsonPath("$.version").value("4.0.20.0"))
                .andExpect(jsonPath("$.isProduction").value(true))
                .andExpect(jsonPath("$.isDocker").value(true))
                .andExpect(jsonPath("$.authentication").value("external"))
                .andExpect(jsonPath("$.urlBase").value(""))
                .andExpect(jsonPath("$.startTime").exists());
    }

    /**
     * What a {@code @WebMvcTest} does not load: the clock (CoreConfig) and the properties, bound from
     * application.properties.
     */
    @TestConfiguration
    @EnableConfigurationProperties(CatasonarrProperties.class)
    static class Beans {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
