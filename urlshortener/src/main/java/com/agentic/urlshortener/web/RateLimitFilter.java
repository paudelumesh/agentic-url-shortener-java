package com.agentic.urlshortener.web;

import java.io.IOException;

import com.agentic.urlshortener.domain.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final TokenBucketRateLimiter limiter;

    public RateLimitFilter(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr();
        if (key == null) {
            key = "unknown";
        }
        if (!limiter.allow(key)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"detail\":\"rate limit exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
