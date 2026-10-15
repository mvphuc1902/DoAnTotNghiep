package org.example.user_module.config;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean
    public org.springframework.security.authentication.dao.DaoAuthenticationProvider databaseAuthentication(DatabaseUsers users, PasswordEncoder encoder) {
        var provider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, DatabaseUsers users) throws Exception {
        http.cors(Customizer.withDefaults()).csrf(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login.html","/login.js","/auth.js","/api/auth/csrf","/api/auth/login","/error","/favicon.ico").permitAll()
                .requestMatchers("/api/auth/me","/api/auth/logout").authenticated()
                .requestMatchers(request -> {
                    var account=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    String path=request.getRequestURI().substring(request.getContextPath().length());
                    return path.startsWith("/api/") && (!path.startsWith("/api/hr/") || path.equals("/api/hr/tasks") || path.startsWith("/api/hr/tasks/") || path.equals("/api/hr/task-people"))
                        && account!=null && account.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_HR"));
                }).denyAll()
                .requestMatchers("/api/erp/tables/users","/api/erp/tables/users/**","/api/erp/schema/users","/api/v1/users/**").hasRole("ADMIN")
                .requestMatchers("/api/erp/dashboard").hasAnyAuthority("ROLE_ADMIN", "ROLE_DIRECTOR", "VIEW_DASHBOARD")
                .requestMatchers("/api/hr/employees","/api/hr/employees/**").hasAnyRole("ADMIN","DIRECTOR","HR")
                .requestMatchers(HttpMethod.DELETE,"/api/hr/recruitment/**","/api/hr/posts/**").hasAnyRole("ADMIN","DIRECTOR","HR")
                .requestMatchers(HttpMethod.DELETE,"/api/**").hasAnyRole("ADMIN", "DIRECTOR")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/api/auth/login")
                .successHandler((request,response,authentication) -> {
                    request.getSession().setAttribute("accountPasswordFingerprint",users.loadUserByUsername(authentication.getName()).getPassword());
                    response.setContentType("application/json");response.getWriter().write("{\"ok\":true}");
                })
                .failureHandler((request,response,error) -> { response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"message\":\"Invalid username or password.\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request,response,authentication) -> response.setStatus(204)))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error) -> {
                if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) response.sendError(401);
                else response.sendRedirect(request.getContextPath() + "/login.html");
            }))
            .requestCache(cache -> cache.disable())
            .addFilterAfter(new AccountRefreshFilter(users),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}




