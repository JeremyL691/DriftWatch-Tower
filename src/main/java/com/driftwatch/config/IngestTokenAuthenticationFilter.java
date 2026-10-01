package com.driftwatch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates the ingest API with an independent bearer token. The token only grants the
 * {@code INGEST} authority; every management endpoint still requires the admin account.
 *
 * <p>Tokens are compared by SHA-256 digest with a constant-time comparison, so neither the
 * value nor its length leaks through timing. A present but invalid bearer header fails the
 * request immediately instead of falling through to anonymous access.
 */
public class IngestTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final List<String> ingestTokens;
    private final AuthenticationEntryPoint entryPoint;

    public IngestTokenAuthenticationFilter(List<String> ingestTokens, AuthenticationEntryPoint entryPoint) {
        this.ingestTokens = ingestTokens == null ? List.of() : List.copyOf(ingestTokens);
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }
        String presented = header.substring(BEARER_PREFIX.length()).trim();
        if (matchesAny(presented)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "ingest-token", null, List.of(new SimpleGrantedAuthority("INGEST")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response, new BadCredentialsException("Invalid ingest token"));
    }

    private boolean matchesAny(String presented) {
        if (presented.isEmpty()) {
            return false;
        }
        byte[] presentedDigest = sha256(presented);
        boolean matched = false;
        for (String token : ingestTokens) {
            if (token != null && !token.isEmpty()) {
                matched |= MessageDigest.isEqual(presentedDigest, sha256(token));
            }
        }
        return matched;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
