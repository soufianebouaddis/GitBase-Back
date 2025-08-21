package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.hook.CodeReviewHook;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class GitServiceImpl implements GitService {

    private final String repositoriesPath = "/var/gitbase/repositories";
    private static final Pattern VALID_REPO_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private final GitRepositoryDB gitRepositoryDB;
    private final UserRepository userRepository;
    public GitServiceImpl(GitRepositoryDB gitRepositoryDB, UserRepository userRepository) {
        this.gitRepositoryDB = gitRepositoryDB;
        this.userRepository = userRepository;
    }

    public void createRepository(String user, String repoName, boolean isPrivate) {
        validateUsername(user);
        validateRepositoryName(repoName);

        String repoPath = repositoriesPath + "/" + user + "/" + repoName + ".git";

        try {
            Path repoDir = Paths.get(repoPath);
            Files.createDirectories(repoDir.getParent());

            org.eclipse.jgit.lib.Repository repo =
                    FileRepositoryBuilder.create(new File(repoPath));
            repo.create(true);

            StoredConfig config = repo.getConfig();
            config.setBoolean("http", null, "receivepack", true);
            config.setBoolean("core", null, "bare", true);
            config.setString("gitbase", null, "visibility", isPrivate ? "private" : "public");
            config.save();
            repo.close();

            // ✅ Persist metadata in PostgreSQL
            RepositoryGit entity = new RepositoryGit();
            entity.setOwner(userRepository.findUserByName(user).get());
            entity.setRepoName(repoName);
            entity.setPrivate(isPrivate);
            gitRepositoryDB.save(entity);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create repository: " + user+ "/" + repoName, e);
        }
    }

    public void handleReceivePack(String username, String repoName,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        // Validate inputs
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        // Check if repository exists
        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build()) {

            ReceivePack receivePack = new ReceivePack(repo);
            receivePack.setPreReceiveHook(new CodeReviewHook());

            response.setContentType("application/x-git-receive-pack-result");
            receivePack.receive(request.getInputStream(), response.getOutputStream(), null);

        } catch (Exception e) {
            throw new RuntimeException("Git receive-pack failed for " + username + "/" + repoName, e);
        }
    }

    /**
     * Constructs the full filesystem path to a Git repository
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return the full path to the repository directory
     */
    private String getRepositoryPath(String username, String repoName) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(repoName)) {
            throw new IllegalArgumentException("Username and repository name cannot be empty");
        }

        // Ensure repository name ends with .git for bare repositories
        String normalizedRepoName = repoName.endsWith(".git") ? repoName : repoName + ".git";

        return Paths.get(repositoriesPath, username, normalizedRepoName).toString();
    }

    /**
     * Checks if a repository exists on the filesystem
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return true if the repository exists, false otherwise
     */
    public boolean repositoryExists(String username, String repoName) {
        try {
            String repoPath = getRepositoryPath(username, repoName);
            File repoDir = new File(repoPath);

            // Check if directory exists and contains Git repository files
            return repoDir.exists() &&
                    repoDir.isDirectory() &&
                    new File(repoDir, "HEAD").exists() &&
                    new File(repoDir, "config").exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the absolute path to a repository and validates it exists
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return the validated repository path
     * @throws IllegalArgumentException if repository doesn't exist
     */
    public String getValidatedRepositoryPath(String username, String repoName) {
        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            throw new IllegalArgumentException("Repository does not exist: " + username + "/" + repoName);
        }

        return repoPath;
    }

    /**
     * Lists all repositories for a given user
     *
     * @return array of repository names (without .git extension)
     */
    public List<RepositoryTreeDto> listRepositories(String user) {
        // Fetch from DB
        List<RepositoryGit> repos = gitRepositoryDB.findByOwner(userRepository.findUserByName(user).get());

        List<RepositoryTreeDto> result = new ArrayList<>();
        for (RepositoryGit repoEntity : repos) {
            String repoPath = Paths.get(repositoriesPath, user, repoEntity.getRepoName() + ".git").toString();

            try (Repository repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                    .setGitDir(new File(repoPath))
                    .build()) {

                // Get latest commit
                try (Git git = new Git(repo)) {
                    Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
                    RevCommit latestCommit = commits.iterator().hasNext() ? commits.iterator().next() : null;

                    FileTreeNode root = null;
                    if (latestCommit != null) {
                        RevTree tree = latestCommit.getTree();
                        root = buildFileTree(repo, tree);
                    }

                    RepositoryTreeDto dto = new RepositoryTreeDto();
                    dto.setRepoName(repoEntity.getRepoName());
                    dto.setPrivate(repoEntity.isPrivate());
                    dto.setRoot(root);

                    result.add(dto);
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to load repository tree for " + repoEntity.getRepoName(), e);
            }
        }
        return result;
    }

    private FileTreeNode buildFileTree(Repository repo, RevTree tree) throws IOException {
        FileTreeNode root = new FileTreeNode("/", true);
        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                addPathToTree(root, path);
            }
        }
        return root;
    }

    private void addPathToTree(FileTreeNode root, String path) {
        String[] parts = path.split("/");
        FileTreeNode current = root;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean isDir = (i < parts.length - 1);

            FileTreeNode child = current.getChildren()
                    .stream()
                    .filter(c -> c.getName().equals(part))
                    .findFirst()
                    .orElse(null);

            if (child == null) {
                child = new FileTreeNode(part, isDir);
                current.getChildren().add(child);
            }

            current = child;
        }
    }

    /**
     * Deletes a repository from the filesystem
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return true if deletion was successful, false otherwise
     */
    public boolean deleteRepository(String username, String repoName) {
        validateUsername(username);
        validateRepositoryName(repoName);

        if (!repositoryExists(username, repoName)) {
            return false;
        }

        String repoPath = getRepositoryPath(username, repoName);

        try {
            Path repoDir = Paths.get(repoPath);
            deleteDirectory(repoDir);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete repository: " + username + "/" + repoName, e);
        }
    }

    /**
     * Gets repository information
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return repository information object
     */
    public RepositoryInfo getRepositoryInfo(String username, String repoName) {
        // ✅ Check from DB first
        Optional<RepositoryGit> repoEntityOpt = gitRepositoryDB.findByOwnerNameAndRepoName(username, repoName);
        RepositoryGit repoEntity = repoEntityOpt.orElseThrow(() ->
                new RuntimeException("Repository not found: " + username + "/" + repoName));

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .build();
             Git git = new Git(repo)) {

            // ✅ Fetch last commit
            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            long lastCommitTime = commits.iterator().hasNext()
                    ? commits.iterator().next().getCommitTime() * 1000L
                    : 0L;

            return new RepositoryInfo(
                    username,
                    repoEntity.getRepoName(),
                    repoEntity.isPrivate(),
                    lastCommitTime
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to get repository info: " + username + "/" + repoName, e);
        }
    }

    // Validation methods
    private void validateUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (!VALID_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid username format: " + username);
        }
        if (username.length() > 100) {
            throw new IllegalArgumentException("Username too long: " + username);
        }
    }

    private void validateRepositoryName(String repoName) {
        if (!StringUtils.hasText(repoName)) {
            throw new IllegalArgumentException("Repository name cannot be empty");
        }

        // Remove .git extension for validation
        String nameToValidate = repoName.endsWith(".git") ?
                repoName.substring(0, repoName.length() - 4) : repoName;

        if (!VALID_REPO_NAME.matcher(nameToValidate).matches()) {
            throw new IllegalArgumentException("Invalid repository name format: " + repoName);
        }
        if (nameToValidate.length() > 100) {
            throw new IllegalArgumentException("Repository name too long: " + repoName);
        }
    }

    // Utility methods
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walk(directory)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

}