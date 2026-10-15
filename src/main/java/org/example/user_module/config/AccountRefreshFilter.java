package org.example.user_module.config;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.web.filter.OncePerRequestFilter;
public class AccountRefreshFilter extends OncePerRequestFilter {
    private final UserDetailsService users;
    public AccountRefreshFilter(UserDetailsService users) { this.users = users; }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UserDetails previous) {
            try {
                var current = users.loadUserByUsername(authentication.getName());
                var session = request.getSession(false);
                Object fingerprint = session == null ? null : session.getAttribute("accountPasswordFingerprint");
                String previousHash = fingerprint == null ? previous.getPassword() : fingerprint.toString();
                if (previousHash != null && !current.getPassword().equals(previousHash)) throw new UsernameNotFoundException("Password changed");
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current,null,current.getAuthorities()));
            } catch (UsernameNotFoundException error) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
            }
        }
        chain.doFilter(request,response);
    }
}
