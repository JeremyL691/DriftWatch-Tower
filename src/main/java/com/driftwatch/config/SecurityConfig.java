package com.driftwatch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Access protection for the self-hosted instance.
 *
 * <ul>
 *   <li>Dashboard, management API, metrics, and API docs use HTTP Basic with the admin account
 *       configured through {@code driftwatch.security.admin.*}. No account database.</li>
 *   <li>The ingest API ({@code POST /api/v1/events}, {@code /batch}) additionally accepts an
 *       independent bearer ingest token that can only ingest.</li>
 *   <li>Browser mutations are CSRF-protected with a readable {@code XSRF-TOKEN} cookie; the
 *       token-authenticated ingest endpoints are exempt because the browser never attaches
 *       that token automatically.</li>
 *   <li>Only {@code /actuator/health} (and the probe groups) is public, and health details are
 *       shown only to an authenticated admin.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DriftwatchProperties properties,
                                                   ObjectMapper objectMapper,
                                                   CsrfTokenRepository csrfTokenRepository) throws Exception {
        AuthenticationEntryPoint entryPoint = apiEntryPoint(objectMapper);
        AccessDeniedHandler deniedHandler = apiAccessDeniedHandler(objectMapper);
        // Default handler keeps a deferred token in the request attribute; CsrfCookieFilter
        // below renders it so the dashboard can read the XSRF-TOKEN cookie.
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/api/v1/events", "/api/v1/events/batch"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/events", "/api/v1/events/batch")
                        .hasAnyAuthority("ROLE_ADMIN", "INGEST")
                        .requestMatchers("/api/**", "/actuator/**", "/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/", "/dashboard/**", "/ws/**")
                        .hasAuthority("ROLE_ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(new IngestTokenAuthenticationFilter(
                                properties.security().ingestTokens(), entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(csrfTokenRepository), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        // Readable by the dashboard JavaScript; also the repository used by tests.
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(DriftwatchProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.security().admin().username())
                .password(encoder.encode(properties.security().admin().password()))
                .roles("ADMIN")
                .build());
    }

    /**
     * Guarantees every browser response carries a readable {@code XSRF-TOKEN} cookie.
     *
     * <p>Spring Security's deferred token only writes the cookie when something asks for the
     * token value, which makes issuance depend on request handling order. This filter renders
     * the deferred token and, when the request arrived without a token cookie, generates and
     * saves one so a browser always has a value to replay in the {@code X-XSRF-TOKEN} header.
     * Requests that already carry a valid cookie keep it; no token is rotated mid-session.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        private final CsrfTokenRepository tokenRepository;

        CsrfCookieFilter(CsrfTokenRepository tokenRepository) {
            this.tokenRepository = tokenRepository;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                throws jakarta.servlet.ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
            if (token == null) {
                token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            }
            if (token != null) {
                token.getToken();
            } else if (tokenRepository.loadToken(request) == null) {
                tokenRepository.saveToken(tokenRepository.generateToken(request), request, response);
            }
            filterChain.doFilter(request, response);
        }
    }

    private AuthenticationEntryPoint apiEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"driftwatch\", Bearer");
            writeJson(response, objectMapper, HttpStatus.UNAUTHORIZED,
                    "authentication required", request.getRequestURI());
        };
    }

    private AccessDeniedHandler apiAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                writeJson(response, objectMapper, HttpStatus.FORBIDDEN,
                        "access denied", request.getRequestURI());
    }

    private static void writeJson(HttpServletResponse response, ObjectMapper objectMapper,
                                  HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", message);
        body.put("path", path);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
