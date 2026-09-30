package com.agentic.urlshortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class UrlApiTest extends BaseApiTest {

    private String createUrl(String url) throws Exception {
        MvcResult result = mvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void createUrlReturnsCodeAndShortUrl() throws Exception {
        String body = createUrl("https://example.com");
        String code = JsonPath.read(body, "$.code");
        String shortUrl = JsonPath.read(body, "$.short_url");
        assertEquals(7, code.length());
        assertTrue(shortUrl.endsWith(code));
    }

    @Test
    void createUrlRejectsInvalidUrl() throws Exception {
        mvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"not-a-url\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void getUrlMetadata() throws Exception {
        String code = JsonPath.read(createUrl("https://example.com"), "$.code");
        mvc.perform(get("/api/urls/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_url").value("https://example.com"));
    }

    @Test
    void getUrlMetadata404ForMissingCode() throws Exception {
        mvc.perform(get("/api/urls/missing")).andExpect(status().isNotFound());
    }

    @Test
    void deleteUrlSoftDeletes() throws Exception {
        String code = JsonPath.read(createUrl("https://example.com"), "$.code");
        mvc.perform(delete("/api/urls/{code}", code)).andExpect(status().isNoContent());
        mvc.perform(get("/api/urls/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
