package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.dto.CommitSummaryDto;
import org.os.gitbase.git.dto.DirEntryDto;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.hook.CodeReviewHook;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class GitServiceImpl implements GitService {

    private final String repositoriesPath = "./gitbase/repositories";
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
            entity.setCreatedAt(LocalDateTime.now());
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
    private void debugRepository(Repository repo, String repoName) throws IOException {
        System.out.println("\n=== DEBUG: " + repoName + " ===");

        // Check HEAD
        Ref headRef = repo.exactRef(Constants.HEAD);
        System.out.println("HEAD ref: " + headRef);
        if (headRef != null) {
            System.out.println("  isSymbolic: " + headRef.isSymbolic());
            System.out.println("  target: " + headRef.getTarget());
            if (headRef.getTarget() != null) {
                System.out.println("  target objectId: " + headRef.getTarget().getObjectId());
            }
        }

        // List all branches
        System.out.println("\nBranches:");
        List<Ref> branches = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        for (Ref branch : branches) {
            System.out.println("  " + branch.getName() + " -> " + branch.getObjectId());
        }

        // Try to read refs/heads/main directly from filesystem
        File mainRef = new File(repo.getDirectory(), "refs/heads/main");
        System.out.println("\nrefs/heads/main file exists: " + mainRef.exists());
        if (mainRef.exists()) {
            String content = new String(java.nio.file.Files.readAllBytes(mainRef.toPath())).trim();
            System.out.println("  content: " + content);
        }

        // Check packed-refs
        File packedRefs = new File(repo.getDirectory(), "packed-refs");
        System.out.println("packed-refs exists: " + packedRefs.exists());
        if (packedRefs.exists()) {
            String content = new String(java.nio.file.Files.readAllBytes(packedRefs.toPath()));
            System.out.println("  content:\n" + content);
        }

        System.out.println("=== END DEBUG ===\n");
    }

    public List<RepositoryTreeDto> listRepositories(String user) {
        List<RepositoryGit> repos = gitRepositoryDB.findByOwner(
                userRepository.findUserByName(user).get()
        );

        List<RepositoryTreeDto> result = new ArrayList<>();

        for (RepositoryGit repoEntity : repos) {
            String repoPath = Paths.get(repositoriesPath, user, repoEntity.getRepoName() + ".git").toString();

            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(new File(repoPath))
                    .setBare()
                    .build()) {

                ObjectId headId = resolveHead(repo); // Smart resolution with auto-fix

                if (headId == null) {
                    System.out.println("⚠️ Repository " + repoEntity.getRepoName() + " is empty");
                    result.add(new RepositoryTreeDto(repoEntity.getRepoName(), repoEntity.isPrivate(), null));
                    continue;
                }

                try (RevWalk revWalk = new RevWalk(repo)) {
                    RevCommit commit = revWalk.parseCommit(headId);
                    RevTree tree = commit.getTree();

                    FileTreeNode root = buildFileTree(repo, tree);

                    RepositoryTreeDto dto = new RepositoryTreeDto();
                    dto.setRepoName(repoEntity.getRepoName());
                    dto.setPrivate(repoEntity.isPrivate());
                    dto.setRoot(root);

                    result.add(dto);
                }

            } catch (Exception e) {
                System.err.println("❌ Failed to load repository: " + repoEntity.getRepoName());
                e.printStackTrace();
                result.add(new RepositoryTreeDto(repoEntity.getRepoName(), repoEntity.isPrivate(), null));
            }
        }

        return result;
    }

    private ObjectId resolveHead(Repository repo) throws IOException {
        repo.getRefDatabase().refresh();

        // Try to resolve HEAD normally
        Ref headRef = repo.exactRef(Constants.HEAD);
        if (headRef != null && headRef.isSymbolic()) {
            Ref target = headRef.getTarget();
            if (target != null && target.getObjectId() != null) {
                return target.getObjectId();
            }
        }

        // HEAD is broken - find and use the actual default branch
        List<String> preferredBranches = Arrays.asList(
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/develop"
        );

        for (String branch : preferredBranches) {
            ObjectId id = repo.resolve(branch);
            if (id != null) {
                updateHeadSilently(repo, branch);
                return id;
            }
        }

        // Use any available branch
        List<Ref> branches = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        if (!branches.isEmpty() && branches.get(0).getObjectId() != null) {
            updateHeadSilently(repo, branches.get(0).getName());
            return branches.get(0).getObjectId();
        }

        return null;
    }

    private void updateHeadSilently(Repository repo, String branchRef) {
        try {
            RefUpdate refUpdate = repo.updateRef(Constants.HEAD, true);
            refUpdate.link(branchRef);
        } catch (IOException e) {
            // Fail silently
        }
    }

    private FileTreeNode buildFileTree(Repository repo, RevTree tree) throws IOException {
        FileTreeNode root = new FileTreeNode("root", true); // Changed from "/" to "root"

        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            int fileCount = 0;
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                addPathToTree(root, path);
                fileCount++;
            }

            System.out.println("📁 Processed " + fileCount + " files");
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
     * Deletes a repository: removes the PostgreSQL metadata row first, then the bare
     * repository directory on disk. Throws {@link ResourceNotFoundException} if no
     * repository exists for the given owner/name.
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     */
    @Override
    public void deleteRepository(String username, String repoName) {
        validateUsername(username);
        validateRepositoryName(repoName);

        RepositoryGit entity = gitRepositoryDB.findByOwnerNameAndRepoName(username, repoName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + username + "/" + repoName));

        // Remove metadata first so the repo disappears from listings even if the
        // filesystem delete partially fails.
        gitRepositoryDB.delete(entity);

        String repoPath = getRepositoryPath(username, repoName);
        try {
            deleteDirectory(Paths.get(repoPath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete repository files: " + username + "/" + repoName, e);
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
                new ResourceNotFoundException("Repository not found: " + username + "/" + repoName));

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

    /**
     * Returns the full recursive file tree for a single repository at the given ref.
     * A null/blank ref resolves to the repository's default branch (HEAD).
     */
    @Override
    public FileTreeNode getTree(String username, String repoName, String ref) {
        validateUsername(username);
        validateRepositoryName(repoName);

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            if (commitId == null) {
                // Empty repository — return an empty root rather than 404.
                return new FileTreeNode("root", true);
            }
            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                return buildFileTree(repo, commit.getTree());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tree for " + username + "/" + repoName, e);
        }
    }

    /**
     * Returns the raw content of a single file (blob) at the given ref. Text files are decoded
     * as UTF-8; binary files return {@code binary=true} with null content.
     */
    @Override
    public FileContentDto getFileContent(String username, String repoName, String ref, String path) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            if (commitId == null) {
                throw new ResourceNotFoundException("Repository is empty: " + username + "/" + repoName);
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
                    if (treeWalk == null) {
                        throw new ResourceNotFoundException("File not found: " + path);
                    }
                    ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                    byte[] bytes = loader.getBytes();
                    boolean binary = isBinary(bytes);
                    String content = binary ? null : new String(bytes, StandardCharsets.UTF_8);
                    String resolvedRef = StringUtils.hasText(ref) ? ref : "HEAD";
                    return new FileContentDto(path, resolvedRef, bytes.length, binary, content);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file " + path + " in " + username + "/" + repoName, e);
        }
    }

    /**
     * GitHub-style listing of one directory level. For each entry we run a path-filtered
     * {@code git log -1} to find the commit that last modified it. Also returns the repo's
     * latest commit on the ref for the "latest commit" bar.
     */
    @Override
    public DirectoryListingDto listContents(String username, String repoName, String ref, String path) {
        validateUsername(username);
        validateRepositoryName(repoName);

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String normPath = path == null ? "" : path.replaceAll("^/+|/+$", "");
        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            String refLabel = StringUtils.hasText(ref) ? ref : "HEAD";

            if (commitId == null) {
                return new DirectoryListingDto(normPath, refLabel, null, new ArrayList<>());
            }

            try (RevWalk revWalk = new RevWalk(repo); Git git = new Git(repo)) {
                RevCommit headCommit = revWalk.parseCommit(commitId);
                CommitSummaryDto latest = toSummary(headCommit);

                // Resolve the tree object whose immediate children we want to list.
                ObjectId treeToList;
                if (normPath.isEmpty()) {
                    treeToList = headCommit.getTree();
                } else {
                    try (TreeWalk sub = TreeWalk.forPath(repo, normPath, headCommit.getTree())) {
                        if (sub == null) {
                            throw new ResourceNotFoundException("Path not found: " + normPath);
                        }
                        if (!sub.getFileMode(0).equals(FileMode.TREE)) {
                            throw new IllegalArgumentException("Not a directory: " + normPath);
                        }
                        treeToList = sub.getObjectId(0);
                    }
                }

                List<DirEntryDto> entries = new ArrayList<>();
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(treeToList);
                    tw.setRecursive(false);
                    while (tw.next()) {
                        String name = tw.getNameString();
                        boolean isDir = tw.isSubtree();
                        String fullPath = normPath.isEmpty() ? name : normPath + "/" + name;
                        CommitSummaryDto last = lastCommitForPath(git, commitId, fullPath);
                        entries.add(new DirEntryDto(
                                name,
                                fullPath,
                                isDir ? "dir" : "file",
                                last != null ? last.getMessage() : null,
                                last != null ? last.getShortSha() : null,
                                last != null ? last.getDate() : 0L
                        ));
                    }
                }

                return new DirectoryListingDto(normPath, refLabel, latest, entries);
            }
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Failed to list contents of " + username + "/" + repoName, e);
        }
    }

    /** Most recent commit that modified {@code path}, starting from {@code start}; null if none. */
    private CommitSummaryDto lastCommitForPath(Git git, ObjectId start, String path) throws GitAPIException, IOException {
        Iterator<RevCommit> it = git.log().add(start).addPath(path).setMaxCount(1).call().iterator();
        return it.hasNext() ? toSummary(it.next()) : null;
    }

    private CommitSummaryDto toSummary(RevCommit commit) {
        String sha = commit.getName();
        String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
        PersonIdent author = commit.getAuthorIdent();
        long dateMillis = commit.getCommitTime() * 1000L;
        return new CommitSummaryDto(
                sha,
                shortSha,
                commit.getShortMessage(),
                author != null ? author.getName() : null,
                author != null ? author.getEmailAddress() : null,
                dateMillis
        );
    }

    /** Resolves a ref name (branch, tag, or SHA) to a commit id; null/blank falls back to HEAD. */
    private ObjectId resolveRef(Repository repo, String ref) throws IOException {
        if (!StringUtils.hasText(ref)) {
            return resolveHead(repo);
        }
        ObjectId id = repo.resolve(ref);
        if (id == null) {
            id = repo.resolve("refs/heads/" + ref);
        }
        return id;
    }

    /** Heuristic binary detection: a NUL byte within the first 8KB marks the blob as binary. */
    private boolean isBinary(byte[] bytes) {
        int len = Math.min(bytes.length, 8000);
        for (int i = 0; i < len; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
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

    public void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build()) {

            UploadPack uploadPack = new UploadPack(repo);
            response.setContentType("application/x-git-upload-pack-result");
            uploadPack.upload(request.getInputStream(), response.getOutputStream(), null);

        } catch (Exception e) {
            throw new RuntimeException("Git upload-pack failed for " + username + "/" + repoName, e);
        }
    }

    public void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build();
             OutputStream out = response.getOutputStream()) {

            if ("git-upload-pack".equals(service)) {
                response.setContentType("application/x-git-upload-pack-advertisement");

                UploadPack uploadPack = new UploadPack(repo);
                RefAdvertiser.PacketLineOutRefAdvertiser advertiser =
                        new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                uploadPack.sendAdvertisedRefs(advertiser);
            } else if ("git-receive-pack".equals(service)) {
                response.setContentType("application/x-git-receive-pack-advertisement");

                ReceivePack rp = new ReceivePack(repo);
                RefAdvertiser.PacketLineOutRefAdvertiser advertiser =
                        new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                rp.sendAdvertisedRefs(advertiser);
            } else {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to handle info/refs for " + username + "/" + repoName, e);
        }
    }

}