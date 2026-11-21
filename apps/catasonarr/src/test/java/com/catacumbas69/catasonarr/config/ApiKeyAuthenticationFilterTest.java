package com.catacumbas69.catasonarr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyAuthenticationFilterTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(KEY);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rightKeyInTheHeader_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/system/status");
        request.addHeader("X-Api-Key", KEY);

        Authentication auth = run(request);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("api-key");
        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    void rightKeyInTheQuery_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/system/status");
        request.setParameter("apikey", KEY);

        assertThat(run(request)).isNotNull();
    }

    @Test
    void wrongOrMissingKey_leavesTheRequestUnauthenticated() throws Exception {
        MockHttpServletRequest wrong = new MockHttpServletRequest("GET", "/api/v3/system/status");
        wrong.addHeader("X-Api-Key", "ffffffffffffffffffffffffffffffff");
        assertThat(run(wrong)).isNull();

        assertThat(run(new MockHttpServletRequest("GET", "/api/v3/system/status"))).isNull();
    }

    @Test
    void emptyKey_isRefused() {
        assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Runs the filter and returns what it left in the security context, read before the chain clears it. */
    private Authentication run(MockHttpServletRequest request) throws Exception {
        SecurityContextHolder.clearContext();
        Authentication[] seen = new Authentication[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }
}
