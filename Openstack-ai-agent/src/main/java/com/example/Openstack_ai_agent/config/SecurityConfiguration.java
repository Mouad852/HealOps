package com.example.Openstack_ai_agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

import java.util.ArrayList;
import java.util.List;

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
            Environment springEnvironment,
            @Value("${healops.security.operator-username:operator}") String operatorUsername,
            @Value("${healops.security.operator-password:operator-local-dev}") String operatorPassword,
            @Value("${healops.security.viewer-username:viewer}") String viewerUsername,
            @Value("${healops.security.viewer-password:viewer-local-dev}") String viewerPassword
    ) {
        boolean demoActive = springEnvironment != null && springEnvironment.acceptsProfiles(Profiles.of("demo"));

        List<String> operatorAuthorities = new ArrayList<>(List.of(
                "ROLE_OPERATOR",
                "incident:read",
                "incident:write",
                "incident:approve",
                "incident:execute",
                "runbook:publish",
                "env:local",
                "env:staging"
        ));
        List<String> viewerAuthorities = new ArrayList<>(List.of(
                "ROLE_VIEWER",
                "incident:read",
                "env:local",
                "env:staging"
        ));
        if (demoActive) {
            operatorAuthorities.add("env:demo");
            viewerAuthorities.add("env:demo");
        }

        UserDetails operator = User.builder()
                .username(operatorUsername)
                .password(passwordEncoder.encode(operatorPassword))
                .authorities(operatorAuthorities.toArray(String[]::new))
                .build();

        UserDetails viewer = User.builder()
                .username(viewerUsername)
                .password(passwordEncoder.encode(viewerPassword))
                .authorities(viewerAuthorities.toArray(String[]::new))
                .build();

        return new InMemoryUserDetailsManager(operator, viewer);
    }
}
