package ru.tz.exchange.adapter.http;

import jakarta.servlet.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import ru.tz.exchange.domain.Actor;

@RestController
public class SessionController {
  public record Login(String username, String password) {}

  private final AuthenticationManager manager;
  private final SecurityContextRepository repository;
  private final Users users;

  public SessionController(
      AuthenticationManager manager, SecurityContextRepository repository, Users users) {
    this.manager = manager;
    this.repository = repository;
    this.users = users;
  }

  @PostMapping("/api/session")
  public Actor login(
      @RequestBody Login login, HttpServletRequest request, HttpServletResponse response) {
    if (login.username() == null || login.password() == null) throw new MissingFields();
    Authentication authentication =
        manager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                login.username(), login.password()));
    request.getSession();
    request.changeSessionId();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    repository.saveContext(context, request, response);
    return users.actor(authentication);
  }

  @GetMapping("/api/me")
  public Actor me(Authentication authentication) {
    return users.actor(authentication);
  }

  @DeleteMapping("/api/session")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    ResponseCookieSupport.clear(response);
  }

  private static class ResponseCookieSupport {
    static void clear(HttpServletResponse response) {
      response.addHeader("Set-Cookie", "SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }
  }
}
