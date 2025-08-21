package org.os.gitbase.git.controller;

import org.os.gitbase.git.service.CommandGitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/git/tokens")
public class GitTokenController {

    private final CommandGitService tokenService;

    public GitTokenController(CommandGitService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createToken(
            Principal principal,
            @RequestParam String name,
            @RequestParam(defaultValue = "repo:read,repo:write") String scopes) {

        String rawToken = tokenService.createToken(
                principal.getName(),
                name,
                scopes,
                Duration.ofDays(360) // expire in 360 days, like GitHub default
        );

        Map<String, String> response = new HashMap<>();
        response.put("token", rawToken);
        response.put("note", "Save this token securely. It will not be shown again.");
        return ResponseEntity.ok(response);
    }
}

