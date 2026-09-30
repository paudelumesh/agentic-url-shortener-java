package com.agentic.urlshortener.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class HealthTest extends BaseApiTest {

    @Test
    void healthzOk() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void readyzOk() throws Exception {
        mvc.perform(get("/readyz")).andExpect(status().isOk());
    }
}
