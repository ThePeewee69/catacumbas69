package com.catacumbas69.catasonarr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sonarr's API authentication (orch.md decision 109): the key in the header {@code X-Api-Key} or in the query
 * parameter {@code apikey}, as Sonarr accepts it. A request with the right key is authenticated as {@link #PRINCIPAL};
 * one without it, or with another key, goes on unauthenticated and the chain answers {@code 401}.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** The header Sonarr's clients send. */
    public static final String HEADER = "X-Api-Key";

    /** The query parameter Sonarr also accepts. */
    public static final String PARAMETER = "apikey";

    /** The name of a request authenticated by the key, and so the actor of its events. */
    public static final String PRINCIPAL = "api-key";

    private final byte[] key;

    /**
     * Creates the filter.
     *
     * @param apiKey the expected key; must not be blank
     */
    public ApiKeyAuthenticationFilter(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("The API key is empty");
        }
        this.key = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        if (!StringUtils.hasText(sent)) {
            sent = request.getParameter(PARAMETER);
        }
        // Compared in constant time, so the answer's timing says nothing about how much of the key was right
        if (StringUtils.hasText(sent) && MessageDigest.isEqual(key, sent.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        }
        chain.doFilter(request, response);
    }
}
