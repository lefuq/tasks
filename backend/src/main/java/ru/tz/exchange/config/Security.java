package ru.tz.exchange.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Security.class);
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(
      org.springframework.security.config.annotation.authentication.configuration
              .AuthenticationConfiguration
          config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository repository)
      throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/session", "/api/me", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .securityContext(context -> context.securityContextRepository(repository))
        .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .requestCache(cache -> cache.disable())
        .formLogin(login -> login.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) -> json(response, 401, "Unauthorized"))
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          LOG.warn("Access denied: {}", error instanceof CsrfException ? "CSRF" : "Forbidden");
                          json(response, 403, "Forbidden");
                        }))
        .addFilterAfter(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (token != null) response.setHeader("X-CSRF-TOKEN", token.getToken());
                chain.doFilter(request, response);
              }
            },
            CsrfFilter.class);
    return http.build();
  }

  private static void json(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + code + "\"}");
  }
}
