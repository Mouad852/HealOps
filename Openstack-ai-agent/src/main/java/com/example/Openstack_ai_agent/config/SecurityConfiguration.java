package com.example.Openstack_ai_agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/incidents/webhooks/**").hasAuthority("incident:write")
                        .requestMatchers("/api/v1/**").authenticated()
                        .requestMatchers("/AskAgent", "/api/AskAgent").authenticated()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/api/v1/incidents/webhooks/**")
                );

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${healops.security.operator-username:operator}") String operatorUsername,
            @Value("${healops.security.operator-password:operator-local-dev}") String operatorPassword,
            @Value("${healops.security.viewer-username:viewer}") String viewerUsername,
            @Value("${healops.security.viewer-password:viewer-local-dev}") String viewerPassword
    ) {
        UserDetails operator = User.builder()
                .username(operatorUsername)
                .password(passwordEncoder.encode(operatorPassword))
                .authorities(
                        "ROLE_OPERATOR",
                        "incident:read",
                        "incident:write",
                        "incident:approve",
                        "incident:execute",
                        "runbook:publish",
                        "env:local",
                        "env:staging"
                )
                .build();

        UserDetails viewer = User.builder()
                .username(viewerUsername)
                .password(passwordEncoder.encode(viewerPassword))
                .authorities(
                        "ROLE_VIEWER",
                        "incident:read",
                        "env:local",
                        "env:staging"
                )
                .build();

        return new InMemoryUserDetailsManager(operator, viewer);
    }
}
