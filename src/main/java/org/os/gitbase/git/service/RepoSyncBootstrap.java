package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * On startup, reconciles every known repository's on-disk git state into the database. This
 * back-fills {@code commits}/{@code branches} for repositories that were pushed before the
 * post-receive sync existed. Idempotent: dedups on commit hash, so repeated boots are cheap.
 */
@Slf4j
@Component
@Order(20)
public class RepoSyncBootstrap implements ApplicationRunner {

    private static final String BASE_PATH = "./gitbase/repositories";

    private final GitRepositoryDB repositoryDB;
    private final PushSyncService pushSyncService;

    public RepoSyncBootstrap(GitRepositoryDB repositoryDB, PushSyncService pushSyncService) {
        this.repositoryDB = repositoryDB;
        this.pushSyncService = pushSyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<RepositoryGit> repos = repositoryDB.findAllWithOwner();
        log.info("Startup reconcile: syncing {} repositories into DB", repos.size());

        for (RepositoryGit r : repos) {
            String username = r.getOwner() != null ? r.getOwner().getName() : null;
            if (username == null || username.isBlank()) {
                continue;
            }
            File gitDir = new File(BASE_PATH + "/" + username + "/" + r.getRepoName() + ".git");
            if (!gitDir.exists()) {
                log.warn("Startup reconcile: repo dir missing for {}/{}", username, r.getRepoName());
                continue;
            }
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir)
                    .setBare()
                    .build()) {
                // Each call runs in its own transaction, so one bad repo can't poison the rest.
                pushSyncService.reconcile(repo, username, r.getRepoName());
            } catch (Exception e) {
                log.error("Startup reconcile failed for {}/{}: {}", username, r.getRepoName(), e.getMessage());
            }
        }
    }
}
