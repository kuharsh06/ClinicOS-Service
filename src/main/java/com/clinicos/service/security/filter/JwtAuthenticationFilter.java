package com.clinicos.service.security.filter;

import com.clinicos.service.config.AppMetrics;
import com.clinicos.service.security.CustomUserDetailsService;
import com.clinicos.service.security.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final AppMetrics appMetrics;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = extractTokenFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                String validationFailure = jwtTokenProvider.validateToken(jwt);
                if (validationFailure == null) {
                    if (jwtTokenProvider.isAccessToken(jwt)) {
                        Integer userId = jwtTokenProvider.getUserId(jwt);
                        String deviceId = jwtTokenProvider.getDeviceId(jwt);

                        UserDetails userDetails = userDetailsService.loadUserById(userId, deviceId);

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails,
                                        null,
                                        userDetails.getAuthorities()
                                );

                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        // Refresh token used as access token
                        appMetrics.recordAuth("jwt_rejected", "failure", "not_access_token");
                    }
                } else {
                    // Specific JWT failure: expired, malformed, unsupported, empty_claims
                    appMetrics.recordAuth("jwt_rejected", "failure", validationFailure);
                }
            }
        } catch (UsernameNotFoundException e) {
            appMetrics.recordAuth("jwt_rejected", "failure", "user_not_found");
            log.error("User not found during JWT auth: {}", e.getMessage());
        } catch (Exception e) {
            appMetrics.recordAuth("jwt_rejected", "failure", "exception");
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }

        return null;
    }
}
