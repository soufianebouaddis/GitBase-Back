package org.os.gitbase.git.service;

import jakarta.servlet.ServletInputStream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.eclipse.jgit.storage.pack.PackConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.entity.GitToken;
import org.os.gitbase.git.repository.GitTokenRepository;
import org.os.gitbase.git.service.GitService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.eclipse.jgit.transport.BasePackPushConnection.*;
@Service
@Slf4j
public class CommandGitService  {
    private final GitTokenRepository repo;
    private final PasswordEncoder passwordEncoder;
    private static final String BASE_PATH = "./gitbase/repositories"; // root path
    private final UserRepository userRepository;
    public CommandGitService(GitTokenRepository repo, PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    private Repository openRepository(String username, String repoName) throws IOException {
        File repoDir = new File(BASE_PATH + "/" + username + "/" + repoName + ".git");
        return new FileRepositoryBuilder()
                .setGitDir(repoDir)
                .readEnvironment()
                .findGitDir()
                .build();
    }
    /**
     * Handle Git info/refs requests - FIXED VERSION
     */
    public void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response) {
        log.debug("Handling info/refs for {}/{} with service {}", username, repoName, service);

        try (Repository repository = openRepository(username, repoName)) {
            if (repository == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
                return;
            }

            // CRITICAL: Set proper headers before any output
            response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "Fri, 01 Jan 1980 00:00:00 GMT");

            // Get output stream ONCE and reuse
            OutputStream rawOut = response.getOutputStream();

            if ("git-upload-pack".equals(service)) {
                response.setContentType("application/x-git-upload-pack-advertisement");

                // Create packet line writer
                PacketLineOut packetOut = new PacketLineOut(rawOut);

                // Write service advertisement
                packetOut.writeString("# service=git-upload-pack\n");
                packetOut.end(); // Send flush packet

                // Create and configure upload pack
                UploadPack up = new UploadPack(repository);

                // Send refs advertisement
                up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(packetOut));

            } else if ("git-receive-pack".equals(service)) {
                response.setContentType("application/x-git-receive-pack-advertisement");

                // Create packet line writer
                PacketLineOut packetOut = new PacketLineOut(rawOut);

                // Write service advertisement
                packetOut.writeString("# service=git-receive-pack\n");
                packetOut.end(); // Send flush packet

                // Create and configure receive pack
                ReceivePack rp = new ReceivePack(repository);
                rp.setCheckReceivedObjects(true);
                rp.setCheckReferencedObjectsAreReachable(true);
                rp.setAllowCreates(true);
                rp.setAllowDeletes(false);
                rp.setAllowNonFastForwards(false);

                // Send refs advertisement
                rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(packetOut));

            } else {
                log.warn("Unsupported service: {}", service);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported service: " + service);
                return;
            }

            // CRITICAL: Ensure all data is written
            rawOut.flush();

            log.debug("Successfully sent advertisement for {}/{} service={}", username, repoName, service);

        } catch (IOException e) {
            log.error("Error in handleInfoRefs for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        }
    }
    /**
     * Handle upload-pack (fetch/clone)
     */
    public void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request, HttpServletResponse response) {
        log.debug("Handling upload-pack for {}/{}", username, repoName);

        try (Repository repository = openRepository(username, repoName)) {
            if (repository == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
                return;
            }

            // Set proper headers
            response.setContentType("application/x-git-upload-pack-result");
            response.setHeader("Cache-Control", "no-cache");

            // Get streams
            ServletInputStream in = request.getInputStream();
            OutputStream out = response.getOutputStream();

            // Create upload pack
            UploadPack up = new UploadPack(repository);

            // CRITICAL FIX: Use NullOutputStream for error stream to prevent protocol confusion
            up.upload(in, out, NullOutputStream.INSTANCE);

            // Ensure all data is sent
            out.flush();

            log.debug("Successfully completed upload-pack for {}/{}", username, repoName);

        } catch (UploadPackInternalServerErrorException e) {
            log.error("Internal server error in upload-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Upload pack internal error");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        } catch (IOException e) {
            log.error("IO error in upload-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Upload failed");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        }
    }
    /**
     * Handle receive-pack (push)
     */
    public void handleReceivePack(String username, String repoName,
                                  HttpServletRequest request, HttpServletResponse response) {
        log.debug("Handling receive-pack for {}/{}", username, repoName);

        try (Repository repository = openRepository(username, repoName)) {
            if (repository == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
                return;
            }

            // Set proper headers
            response.setContentType("application/x-git-receive-pack-result");
            response.setHeader("Cache-Control", "no-cache");

            // Get streams
            ServletInputStream in = request.getInputStream();
            OutputStream out = response.getOutputStream();

            // Create receive pack with proper configuration
            ReceivePack rp = new ReceivePack(repository);
            rp.setCheckReceivedObjects(true);
            rp.setCheckReferencedObjectsAreReachable(true);
            rp.setAllowCreates(true);
            rp.setAllowDeletes(false);
            rp.setAllowNonFastForwards(false);

            // Add basic logging hooks
            rp.setPreReceiveHook(new PreReceiveHook() {
                @Override
                public void onPreReceive(ReceivePack receivePack, java.util.Collection<ReceiveCommand> commands) {
                    log.debug("Pre-receive: Processing {} commands for {}/{}",
                            commands.size(), username, repoName);
                    for (ReceiveCommand cmd : commands) {
                        log.debug("Command: {} {} -> {}",
                                cmd.getRefName(),
                                cmd.getOldId().name(),
                                cmd.getNewId().name());
                    }
                }
            });

            rp.setPostReceiveHook(new PostReceiveHook() {
                @Override
                public void onPostReceive(ReceivePack receivePack, java.util.Collection<ReceiveCommand> commands) {
                    long successCount = commands.stream()
                            .mapToLong(cmd -> cmd.getResult() == ReceiveCommand.Result.OK ? 1 : 0)
                            .sum();
                    log.info("Post-receive: {} successful commands for {}/{}",
                            successCount, username, repoName);
                }
            });

            // CRITICAL FIX: This is the key fix for your "bad band #50" error
            // Use NullOutputStream for error stream to prevent protocol confusion
            rp.receive(in, out, NullOutputStream.INSTANCE);

            // Ensure all data is sent
            out.flush();

            log.debug("Successfully completed receive-pack for {}/{}", username, repoName);

        } catch (IOException e) {
            log.error("Error in receive-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            // NOTE: Don't try to send error response here if receive() has started
            // The Git protocol may already be in progress and sending HTTP error would break it
            // JGit's ReceivePack will handle sending appropriate Git protocol error messages
        } catch (Exception e) {
            log.error("Unexpected error in receive-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Push failed");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        }
    }
    public String createToken(String user, String name, String scopes, Duration validity) {

        String rawToken = UUID.randomUUID().toString().replace("-", "");
        String hash = passwordEncoder.encode(rawToken); // uses Argon2

        GitToken entity = new GitToken();
        entity.setUser(userRepository.findUserByName(user).get());
        entity.setName(name);
        entity.setTokenHash(hash);
        entity.setScopes(scopes);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plus(validity));

        repo.save(entity);

        return rawToken; // only return once
    }

    public boolean validate(String username, String rawToken) {
        List<GitToken> tokens = repo.findByUsername(username);

        return tokens.stream()
                .anyMatch(t -> passwordEncoder.matches(rawToken, t.getTokenHash())
                        && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now())));
    }



}

