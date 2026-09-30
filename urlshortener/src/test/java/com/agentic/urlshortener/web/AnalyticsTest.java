package com.agentic.urlshortener.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AnalyticsTest extends BaseApiTest {

    private String createCode() throws Exception {
        MvcResult result = mvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void analyticsReportsTotalClicks() throws Exception {
        String code = createCode();
        mvc.perform(get("/{code}", code)).andExpect(status().isFound());
        mvc.perform(get("/{code}", code)).andExpect(status().isFound());
        mvc.perform(get("/api/urls/{code}/analytics", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_clicks").value(2));
    }

    @Test
    void analyticsReportsTopReferrers() throws Exception {
        String code = createCode();
        mvc.perform(get("/{code}", code).header("Referer", "google.com")).andExpect(status().isFound());
        mvc.perform(get("/{code}", code).header("Referer", "google.com")).andExpect(status().isFound());
        mvc.perform(get("/{code}", code).header("Referer", "direct")).andExpect(status().isFound());
        mvc.perform(get("/api/urls/{code}/analytics", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_clicks").value(3))
                .andExpect(jsonPath("$.top_referrers[0].referrer").value("google.com"))
                .andExpect(jsonPath("$.top_referrers[0].count").value(2));
    }

    @Test
    void analytics404ForMissingCode() throws Exception {
        mvc.perform(get("/api/urls/missing/analytics")).andExpect(status().isNotFound());
    }
}
