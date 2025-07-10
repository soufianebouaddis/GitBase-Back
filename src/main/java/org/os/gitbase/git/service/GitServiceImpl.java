package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.ReceivePack;
import org.os.gitbase.git.hook.CodeReviewHook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Pattern;

@Service
public class GitServiceImpl implements GitService {

    private final String repositoriesPath = "/var/gitbase/repositories";
    private static final Pattern VALID_REPO_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9._-]+$");

    public void createRepository(String username, String repoName, boolean isPrivate) {
        // Validate inputs
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        try {
            // Create directory structure if it doesn't exist
            Path repoDir = Paths.get(repoPath);
            Files.createDirectories(repoDir.getParent());

            Repository repo = FileRepositoryBuilder.create(new File(repoPath));
            repo.create(true); // bare repository

            // Set repository configuration
            StoredConfig config = repo.getConfig();
            config.setBoolean("http", null, "receivepack", true);
            config.setBoolean("core", null, "bare", true);

            // Set privacy configuration
            if (isPrivate) {
                config.setString("gitbase", null, "visibility", "private");
            } else {
                config.setString("gitbase", null, "visibility", "public");
            }

            config.save();
            repo.close();

        } catch (IOException e) {
            throw new RuntimeException("Failed to create repository: " + username + "/" + repoName, e);
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
     * @param username the user whose repositories to list
     * @return array of repository names (without .git extension)
     */
    public String[] listRepositories(String username) {
        validateUsername(username);

        Path userPath = Paths.get(repositoriesPath, username);
        File userDir = userPath.toFile();

        if (!userDir.exists() || !userDir.isDirectory()) {
            return new String[0];
        }

        File[] repos = userDir.listFiles(file ->
                file.isDirectory() &&
                        file.getName().endsWith(".git") &&
                        new File(file, "HEAD").exists()
        );

        if (repos == null) {
            return new String[0];
        }

        return Arrays.stream(repos)
                .map(File::getName)
                .map(name -> name.substring(0, name.length() - 4)) // Remove .git extension
                .toArray(String[]::new);
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
        String repoPath = getValidatedRepositoryPath(username, repoName);

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build()) {

            StoredConfig config = repo.getConfig();
            boolean isPrivate = "private".equals(config.getString("gitbase", null, "visibility"));

            File repoDir = new File(repoPath);
            long lastModified = repoDir.lastModified();

            return new RepositoryInfo(username, repoName, isPrivate, lastModified);

        } catch (IOException e) {
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

    // Inner class for repository information
    public static class RepositoryInfo {
        private final String username;
        private final String repoName;
        private final boolean isPrivate;
        private final long lastModified;

        public RepositoryInfo(String username, String repoName, boolean isPrivate, long lastModified) {
            this.username = username;
            this.repoName = repoName;
            this.isPrivate = isPrivate;
            this.lastModified = lastModified;
        }

        // Getters
        public String getUsername() { return username; }
        public String getRepoName() { return repoName; }
        public boolean isPrivate() { return isPrivate; }
        public long getLastModified() { return lastModified; }
    }
}