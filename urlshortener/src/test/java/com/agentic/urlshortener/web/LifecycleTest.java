package com.agentic.urlshortener.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class LifecycleTest extends BaseApiTest {

    @Test
    void fullLifecycleCreateRedirectAnalyticsDelete() throws Exception {
        MvcResult created = mvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/page\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.code");

        mvc.perform(get("/{code}", code)).andExpect(status().isFound());

        mvc.perform(get("/api/urls/{code}/analytics", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_clicks").value(1));

        mvc.perform(delete("/api/urls/{code}", code)).andExpect(status().isNoContent());

        mvc.perform(get("/{code}", code)).andExpect(status().is(410));
    }
}
