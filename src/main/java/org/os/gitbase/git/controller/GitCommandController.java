package org.os.gitbase.git.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.git.service.CommandGitService;
import org.springframework.web.bind.annotation.*;
/*
@RestController
@RequestMapping("/api/v1/gitbase/{username}/{repoName}.git")*/
public class GitCommandController {

    private final CommandGitService gitService;

    public GitCommandController(CommandGitService gitService) {
        this.gitService = gitService;
    }

    //@GetMapping("/info/refs")
    /*public void getInfoRefs(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(name = "service") String service,
            HttpServletResponse response) {
        gitService.handleInfoRefs(username, repoName, service, response);
    }

    //@PostMapping("/git-upload-pack")
    public void uploadPack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {
        gitService.handleUploadPack(username, repoName, request, response);
    }

    //@PostMapping("/git-receive-pack")
    public void receivePack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {
        gitService.handleReceivePack(username, repoName, request, response);
    }*/
}
