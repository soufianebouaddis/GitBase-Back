package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface GitService {
    void createRepository(String username, String repoName, boolean isPrivate);

    void handleReceivePack(String username, String repoName, HttpServletRequest request, HttpServletResponse response);
}
