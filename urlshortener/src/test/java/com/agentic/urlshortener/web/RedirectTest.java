package com.agentic.urlshortener.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class RedirectTest extends BaseApiTest {

    private String createCode() throws Exception {
        MvcResult result = mvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void redirectFollowsToTarget() throws Exception {
        String code = createCode();
        mvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void redirect404ForMissingCode() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound());
    }

    @Test
    void redirect410ForDeletedCode() throws Exception {
        String code = createCode();
        mvc.perform(delete("/api/urls/{code}", code)).andExpect(status().isNoContent());
        mvc.perform(get("/{code}", code)).andExpect(status().is(410));
    }

    @Test
    void redirectRecordsAClick() throws Exception {
        String code = createCode();
        mvc.perform(get("/{code}", code)).andExpect(status().isFound());
        mvc.perform(get("/api/urls/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.click_count").value(1));
    }
}
