package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;

import java.util.List;

public interface GitService {
    void createRepository(String user, String repoName, boolean isPrivate);
    RepositoryInfo getRepositoryInfo(String username, String repoName);
    List<RepositoryTreeDto> listRepositories(String user);
    void handleReceivePack(String username, String repoName, HttpServletRequest request, HttpServletResponse response);
    void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request,
                                 HttpServletResponse response);
    void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response);
}
