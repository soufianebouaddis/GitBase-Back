package org.os.gitbase.git.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.auth.service.UserService;
import org.os.gitbase.git.dto.CreateRepositoryDto;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.service.GitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.os.gitbase.constant.Constant.*;
@RequestMapping(GITBASE_MAPPING_REQUEST)
@RestController
public class GitController {
    private GitService gitService;

    public GitController(GitService gitService) {
        this.gitService = gitService;
    }
    @PostMapping(CREATE_REPOSITORY)
    public ResponseEntity<?> createRepository(@RequestBody CreateRepositoryDto request) {
        gitService.createRepository(request.getUsername(),
                request.getRepoName(),
                request.isPrivate());
        String remoteUrl = "http://localhost:8880/api/v1/gitbase/" + request.getUsername() + "/" + request.getRepoName() + ".git";
        List<String> gitCommands = Arrays.asList(
                "git init",
                "git add .",
                "git branch -M " + (request.getDefaultBranch().isEmpty() ? "main" : request.getDefaultBranch()),
                "git commit -m \"first commit\"",
                "git remote add origin " + remoteUrl,
                "git push -u origin " + (request.getDefaultBranch().isEmpty() ? "main" : request.getDefaultBranch())
        );

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Repository created successfully");
        response.put("repository", request.getRepoName());
        response.put("gitCommands", gitCommands);
        return ResponseEntity.ok(response);
    }

    // -------------------- GET REPOSITORY INFO --------------------
    @GetMapping(REPOSITORY_INFO)
    public ResponseEntity<RepositoryInfo> getRepositoryInfo(@RequestParam String username,
                                                            @RequestParam String repoName) {
        RepositoryInfo info = gitService.getRepositoryInfo(username, repoName);
        return ResponseEntity.ok(info);
    }

    // -------------------- LIST ALL USER REPOSITORIES --------------------
    @GetMapping(REPOSITORIES+"/{username}")
    public ResponseEntity<?> listRepositories(@PathVariable String username) {
        List<?> repos = gitService.listRepositories(username);
        return ResponseEntity.ok(repos);
    }


    // --- Git advertises services (push/pull support) ---
    @GetMapping("/info/refs")
    public void getInfoRefs(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(name = "service") String service,
            HttpServletResponse response) {

        gitService.handleInfoRefs(username, repoName, service, response);
    }

    @PostMapping("/git-receive-pack")
    public void receivePack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {
        gitService.handleReceivePack(username, repoName, request, response);
    }

    // --- Git fetch/clone endpoint ---
    @PostMapping("/git-upload-pack")
    public void uploadPack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {
        gitService.handleUploadPack(username, repoName, request, response);
    }
}
