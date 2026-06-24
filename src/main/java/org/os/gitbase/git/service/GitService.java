package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;

import java.util.List;

public interface GitService {
    void createRepository(String user, String repoName, boolean isPrivate);
    RepositoryInfo getRepositoryInfo(String username, String repoName);
    List<RepositoryTreeDto> listRepositories(String user);
    void deleteRepository(String user, String repoName);

    /** Full recursive file tree for a single repository at the given ref (null/blank = default branch). */
    FileTreeNode getTree(String username, String repoName, String ref);

    /** Raw content of a single file (blob) at the given ref. */
    FileContentDto getFileContent(String username, String repoName, String ref, String path);

    /**
     * GitHub-style listing of one directory level at the given path/ref, where each entry
     * carries the commit that last modified it, plus the repo's latest commit on the ref.
     */
    DirectoryListingDto listContents(String username, String repoName, String ref, String path);
    void handleReceivePack(String username, String repoName, HttpServletRequest request, HttpServletResponse response);
    void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request,
                                 HttpServletResponse response);
    void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response);
}
