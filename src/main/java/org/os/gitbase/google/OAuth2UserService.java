package org.os.gitbase.google;


import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
public class OAuth2UserService extends DefaultOAuth2UserService {
    private final UserRepository userRepository;
    public OAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest oAuth2UserRequest) throws OAuth2AuthenticationException {
        log.info("OAuth2UserService.loadUser called for registration: {}", oAuth2UserRequest.getClientRegistration().getRegistrationId());
        
        OAuth2User oAuth2User = super.loadUser(oAuth2UserRequest);
        log.info("OAuth2User loaded: {}", oAuth2User.getName());

        try {
            OAuth2User processedUser = processOAuth2User(oAuth2UserRequest, oAuth2User);
            log.info("OAuth2User processed successfully: {}", processedUser.getName());
            return processedUser;
        } catch (Exception ex) {
            log.error("Error processing OAuth2 user", ex);
            throw new OAuth2AuthenticationException("Error processing OAuth2 user");
        }
    }
    private OAuth2User processOAuth2User(OAuth2UserRequest oAuth2UserRequest, OAuth2User oAuth2User) {
        GoogleOAuth2UserInfo oAuth2UserInfo = new GoogleOAuth2UserInfo(oAuth2User.getAttributes());

        if (oAuth2UserInfo.getEmail() == null || oAuth2UserInfo.getEmail().isEmpty()) {
            throw new OAuth2AuthenticationException("Email not found from OAuth2 provider");
        }

        User user = userRepository.findUserByEmail(oAuth2UserInfo.getEmail())
                .map(existingUser -> updateExistingUser(existingUser, oAuth2UserInfo))
                .orElse(registerNewUser(oAuth2UserInfo));

        return UserPrincipal.create(user, oAuth2User.getAttributes());
    }
    private User registerNewUser(GoogleOAuth2UserInfo oAuth2UserInfo) {
        User user = new User(
                oAuth2UserInfo.getName(),
                oAuth2UserInfo.getEmail(),
                oAuth2UserInfo.getId(),
                oAuth2UserInfo.getImageUrl()
        );

        log.info("Registering new user: {}", user.getEmail());
        return userRepository.save(user);
    }

    private User updateExistingUser(User existingUser, GoogleOAuth2UserInfo oAuth2UserInfo) {
        existingUser.setName(oAuth2UserInfo.getName());
        existingUser.setProfilePictureUrl(oAuth2UserInfo.getImageUrl());
        existingUser.setLastLogin(LocalDateTime.now());
        log.info("Updating existing user: {}", existingUser.getEmail());
        return userRepository.save(existingUser);
    }
}
