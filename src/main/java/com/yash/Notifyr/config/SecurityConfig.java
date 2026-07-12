package com.yash.Notifyr.config;

import org.springframework.http.HttpMethod;
import com.yash.Notifyr.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/templates/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/templates/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/campaigns/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/campaigns/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/campaigns/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
