package org.os.gitbase.git.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.git.service.CommandGitService;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/api/v1/gitbase/{username}/{repoName}.git")
public class GitCommandController {

    private final CommandGitService gitService;

    public GitCommandController(CommandGitService gitService) {
        this.gitService = gitService;
    }

    /**
     * Advertise refs (info/refs endpoint).
     * Spring async via Callable ensures raw Git pkt-lines stream correctly.
     */
    @GetMapping("/info/refs")
    public void getInfoRefs(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(name = "service") String service,
            HttpServletResponse response) {
        gitService.handleInfoRefs(username, repoName, service, response);
    }

    /**
     * Upload-pack (fetch/clone).
     */
    @PostMapping("/git-upload-pack")
    public void uploadPack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {

        gitService.handleUploadPack(username, repoName, request, response);
    }

    /**
     * Receive-pack (push).
     */
    @PostMapping("/git-receive-pack")
    public void receivePack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {
        gitService.handleReceivePack(username, repoName, request, response);
    }
}
