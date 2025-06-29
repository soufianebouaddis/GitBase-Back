package org.os.gitbase.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.dto.AuthResponse;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.google.UserPrincipal;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.os.gitbase.constant.Constant;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private JwtTokenProvider tokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        log.info("OAuth2AuthenticationSuccessHandler called");
        log.info("Authentication principal type: {}", authentication.getPrincipal().getClass().getName());
        log.info("Authentication principal: {}", authentication.getPrincipal());

        try {
            // Handle different types of OAuth2 users
            String email = null;
            String name = null;
            String profilePictureUrl = null;
            List<String> roles = new ArrayList<>();
            
            if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser) {
                org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser oidcUser = 
                    (org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser) authentication.getPrincipal();
                
                email = oidcUser.getEmail();
                name = oidcUser.getName();
                profilePictureUrl = oidcUser.getPicture();
                roles = oidcUser.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
                        
            } else if (authentication.getPrincipal() instanceof UserPrincipal) {
                UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
                email = userPrincipal.getEmail();
                name = userPrincipal.getName();
                if (userPrincipal.getAttributes() != null) {
                    profilePictureUrl = (String) userPrincipal.getAttributes().get("picture");
                }
                roles = userPrincipal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
            } else {
                // Fallback for other OAuth2 user types
                email = authentication.getName();
                name = authentication.getName();
                roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
            }
            
            if (email == null) {
                throw new RuntimeException("Email not found in OAuth2 user");
            }

            String accessToken = tokenProvider.generateToken(email, roles);
            
            // For OAuth2 users, create a simple refresh token without requiring database user
            RefreshToken refreshToken;
            try {
                refreshToken = tokenProvider.createRefreshToken(email);
            } catch (Exception e) {
                log.warn("Could not create refresh token for OAuth2 user: {}. Creating simple token.", email);
                // Create a simple refresh token for OAuth2 users
                refreshToken = RefreshToken.builder()
                        .token(UUID.randomUUID().toString())
                        .expiryDate(Instant.now().plusMillis(28800000)) // 8 hours
                        .build();
            }

            // Create user info
            UserInfo userInfo = new UserInfo(
                    UUID.randomUUID(), // Generate a new UUID for OAuth2 users
                    name,
                    email,
                    profilePictureUrl,
                    roles.stream()
                            .map(role -> org.os.gitbase.auth.entity.Role.builder()
                                    .roleName(role)
                                    .build())
                            .collect(Collectors.toSet())
            );

            // Create auth response
            AuthResponse authResponse = AuthResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken.getToken())
                    .tokenType("Bearer")
                    .expiresIn(3600L)
                    .user(userInfo)
                    .build();

            // Create API response wrapper
            ApiResponseEntity<AuthResponse> apiResponse = new ApiResponseEntity<>(
                    Instant.now(),
                    true,
                    "OAuth2 authentication successful",
                    HttpStatus.OK,
                    authResponse
            );

            // Set cookies
            Cookie accessTokenCookie = new Cookie(Constant.ACCESS_TOKEN, accessToken);
            accessTokenCookie.setPath("/");
            accessTokenCookie.setHttpOnly(true);
            accessTokenCookie.setSecure(false); // Set to true in production with HTTPS
            accessTokenCookie.setMaxAge(3600); // 1 hour
            response.addCookie(accessTokenCookie);

            Cookie refreshTokenCookie = new Cookie(Constant.REFRESH_TOKEN, refreshToken.getToken());
            refreshTokenCookie.setPath("/");
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setSecure(false); // Set to true in production with HTTPS
            refreshTokenCookie.setMaxAge(7 * 24 * 3600); // 7 days
            response.addCookie(refreshTokenCookie);

            // Set response headers
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            // Write JSON response
            String jsonResponse = objectMapper.writeValueAsString(apiResponse);
            response.getWriter().write(jsonResponse);

            log.info("OAuth2 login successful for user: {}", email);
            
            // Clear authentication attributes
            clearAuthenticationAttributes(request);
            
        } catch (Exception e) {
            log.error("Error in OAuth2 success handler", e);
            
            // Return error response as JSON
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            
            ApiResponseEntity<String> errorResponse = new ApiResponseEntity<>(
                    Instant.now(),
                    false,
                    "OAuth2 authentication failed: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null
            );
            
            String errorJson = objectMapper.writeValueAsString(errorResponse);
            response.getWriter().write(errorJson);
        }
    }

    private void clearAuthenticationAttributes(HttpServletRequest request) {
        request.getSession().removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
    }
}