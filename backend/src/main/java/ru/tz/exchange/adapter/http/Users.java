package ru.tz.exchange.adapter.http;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Component;
import ru.tz.exchange.application.Ports;
import ru.tz.exchange.domain.Actor;

@Component
public class Users implements UserDetailsService {
  private final Ports.Directory directory;

  public Users(Ports.Directory directory) {
    this.directory = directory;
  }

  public UserDetails loadUserByUsername(String username) {
    Ports.Identity identity = directory.identity(username);
    if (identity == null) throw new UsernameNotFoundException("Unauthorized");
    return User.withUsername(identity.username())
        .password(identity.passwordHash())
        .roles(identity.actor().role().name())
        .build();
  }

  public Actor actor(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getName().equals("anonymousUser"))
      throw new org.springframework.security.authentication.BadCredentialsException("Unauthorized");
    Ports.Identity identity = directory.identity(authentication.getName());
    if (identity == null)
      throw new org.springframework.security.authentication.BadCredentialsException("Unauthorized");
    return identity.actor();
  }
}
