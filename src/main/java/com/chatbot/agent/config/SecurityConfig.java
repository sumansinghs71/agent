package com.chatbot.agent.config;

import com.chatbot.agent.security.Roles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security.
 *
 * <p>Replaces a configuration in which {@code /api/**} and {@code /actuator/**} were both
 * {@code permitAll()}. That combination meant an unauthenticated caller could POST a tool
 * containing arbitrary Python to {@code /api/tools/{id}} and then execute it - see
 * {@code docs/KNOWN_LIMITATIONS.md} finding F-1.
 *
 * <h2>Authority model</h2>
 * <ul>
 *   <li>{@code ROLE_USER} — may chat and invoke READ_ONLY tools.</li>
 *   <li>{@code ROLE_OPERATOR} — additionally REVERSIBLE_WRITE tools.</li>
 *   <li>{@code ROLE_ADMIN} — may register, update and delete tools, and invoke PRIVILEGED tools
 *       (Python/JavaScript). Tool authoring is an administrative act: a tool body <em>is</em> code.</li>
 * </ul>
 *
 * <p>The URL rules here are the outer boundary. They are not the whole story: which tool a caller
 * may actually run is decided per-invocation by {@code ToolInvocationPolicy}, because the HTTP path
 * does not reveal the side-effect class of the tool named in the request body.
 *
 * <p>The in-memory user store is a development convenience and is documented as such. It reads
 * passwords from the environment and <b>refuses to start</b> if they are absent, so there is no
 * default credential to forget about.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String userPassword;
    private final String operatorPassword;
    private final String adminPassword;

    public SecurityConfig(
            @Value("${AGENT_USER_PASSWORD:}") String userPassword,
            @Value("${AGENT_OPERATOR_PASSWORD:}") String operatorPassword,
            @Value("${AGENT_ADMIN_PASSWORD:}") String adminPassword) {
        this.userPassword = userPassword;
        this.operatorPassword = operatorPassword;
        this.adminPassword = adminPassword;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API with HTTP Basic: there is no browser session or cookie for an
                // attacker to ride, so CSRF protection has nothing to protect here.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // --- the only anonymous surface ---
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // --- tool authoring is administrative: a tool body is executable code ---
                        .requestMatchers(HttpMethod.POST, "/api/tools/*").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/tools/**").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/tools/**").hasRole(Roles.ADMIN)

                        // --- invocation: authenticated; the per-tool decision is the policy's ---
                        .requestMatchers(HttpMethod.POST, "/api/tools/*/execute").authenticated()
                        .requestMatchers("/api/**").authenticated()

                        // --- everything else, including the rest of actuator, is closed ---
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Development user store.
     *
     * <p>Startup fails if any password is unset. The previous version shipped {@code user/password}
     * and {@code admin/admin} as literals, which is a default credential in everything but name.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        requirePassword("AGENT_USER_PASSWORD", userPassword);
        requirePassword("AGENT_OPERATOR_PASSWORD", operatorPassword);
        requirePassword("AGENT_ADMIN_PASSWORD", adminPassword);

        UserDetails user = User.builder()
                .username("user")
                .password(encoder.encode(userPassword))
                .roles(Roles.USER)
                .build();

        UserDetails operator = User.builder()
                .username("operator")
                .password(encoder.encode(operatorPassword))
                .roles(Roles.USER, Roles.OPERATOR)
                .build();

        UserDetails admin = User.builder()
                .username("admin")
                .password(encoder.encode(adminPassword))
                .roles(Roles.USER, Roles.OPERATOR, Roles.ADMIN)
                .build();

        return new InMemoryUserDetailsManager(user, operator, admin);
    }

    private static void requirePassword(String variable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    variable + " is not set. The bundled development user store has no default "
                    + "passwords by design. Set " + variable + " (see .env.example), or replace "
                    + "SecurityConfig#userDetailsService with a real identity provider.");
        }
    }
}
