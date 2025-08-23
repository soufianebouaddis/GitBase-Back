package org.os.gitbase.git.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.git.service.CommandGitService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
/*
@Controller
@RequestMapping("/api/v1/gitbase/{username}/{repoName}.git")
public class GitCommandController {

    private final CommandGitService gitService;

    public GitCommandController(CommandGitService gitService) {
        this.gitService = gitService;
    }


    private void configureGitRequest(HttpServletRequest request, HttpServletResponse response) {
        // Disable async mode (Tomcat default can corrupt binary streams)
        request.setAttribute("org.apache.catalina.ASYNC_SUPPORTED", false);

        // Make sure no compression happens
        response.setHeader("Content-Encoding", "identity");

        // Disable caching
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        // Use a larger buffer for Git network traffic
        response.setBufferSize(65536); // 64 KB
    }

    private void configureReceivePack(HttpServletRequest request, HttpServletResponse response) {
        configureGitRequest(request, response);
        response.setBufferSize(131072); // 128 KB for pushes
        response.setContentType("application/x-git-receive-pack-result");
    }

    private void configureUploadPack(HttpServletRequest request, HttpServletResponse response) {
        configureGitRequest(request, response);
        response.setContentType("application/x-git-upload-pack-result");
    }

    private void configureInfoRefs(String service, HttpServletResponse response) {
        if ("git-upload-pack".equals(service)) {
            response.setContentType("application/x-git-upload-pack-advertisement");
        } else if ("git-receive-pack".equals(service)) {
            response.setContentType("application/x-git-receive-pack-advertisement");
        }
    }

    @GetMapping("/info/refs")
    public void getInfoRefs(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(name = "service") String service,
            HttpServletRequest request,
            HttpServletResponse response) {

        configureGitRequest(request, response);
        configureInfoRefs(service, response);

        gitService.handleInfoRefs(username, repoName, service, response);
    }

    @PostMapping("/git-upload-pack")
    public void uploadPack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {

        configureUploadPack(request, response);
        gitService.handleUploadPack(username, repoName, request, response);
    }

    @PostMapping("/git-receive-pack")
    public void receivePack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {

        configureReceivePack(request, response);
        gitService.handleReceivePack(username, repoName, request, response);
    }
}
*/





import java.util.concurrent.Callable;

@Controller
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
    @ResponseBody
    public Callable<Void> getInfoRefs(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(name = "service") String service,
            HttpServletResponse response) {

        return () -> {
            gitService.handleInfoRefs(username, repoName, service, response);
            return null;
        };
    }

    /**
     * Upload-pack (fetch/clone).
     */
    @PostMapping("/git-upload-pack")
    @ResponseBody
    public Callable<Void> uploadPack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {

        return () -> {
            gitService.handleUploadPack(username, repoName, request, response);
            return null;
        };
    }

    /**
     * Receive-pack (push).
     */
    @PostMapping("/git-receive-pack")
    @ResponseBody
    public Callable<Void> receivePack(
            @PathVariable String username,
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response) {

        return () -> {
            gitService.handleReceivePack(username, repoName, request, response);
            return null;
        };
    }
}
