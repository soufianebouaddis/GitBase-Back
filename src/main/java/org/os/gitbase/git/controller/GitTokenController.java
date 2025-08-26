package org.os.gitbase.git.controller;

import org.os.gitbase.constant.Constant;
import org.os.gitbase.git.dto.CreateTokenDto;
import org.os.gitbase.git.dto.GitTokenInfo;
import org.os.gitbase.git.service.CommandGitService;
import org.os.gitbase.helper.Helper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(Constant.GITBASE_MAPPING_REQUEST+"/tokens")
public class GitTokenController {

    private final CommandGitService tokenService;

    public GitTokenController(CommandGitService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createToken(
            Principal principal,
            @RequestBody CreateTokenDto createTokenDto
    ) {

        String rawToken = tokenService.createToken(
                Helper.removeAtSymbolAndFollowing(principal.getName()),
                createTokenDto.getName(),
                createTokenDto.getScopes(),
                Duration.ofDays(360) // expire in 360 days, like GitHub default
        );

        Map<String, String> response = new HashMap<>();
        response.put("token", rawToken);
        response.put("note", "Save this token securely. It will not be shown again.");
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<GitTokenInfo>> getTokens(Principal principal) {
        List<GitTokenInfo> tokens = tokenService.getTokens(Helper.removeAtSymbolAndFollowing(principal.getName()));

        return ResponseEntity.ok(tokens);
    }
}

