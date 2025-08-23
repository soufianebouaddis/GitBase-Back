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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
     * Handle Git info/refs requests (advertises refs for fetch/push)
     */
    public void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response) {
        log.debug("Handling info/refs for {}/{} with service {}", username, repoName, service);

        try (Repository repository = openRepository(username, repoName)) {
            if (repository == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
                return;
            }

            // Required headers for Git Smart HTTP
            response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "Fri, 01 Jan 1980 00:00:00 GMT");

            try (OutputStream rawOut = response.getOutputStream()) {
                PacketLineOut packetOut = new PacketLineOut(rawOut);

                if ("git-upload-pack".equals(service)) {
                    response.setContentType("application/x-git-upload-pack-advertisement");

                    // Required prefix
                    packetOut.writeString("# service=git-upload-pack\n");
                    packetOut.end();

                    UploadPack up = new UploadPack(repository);
                    up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(packetOut));

                } else if ("git-receive-pack".equals(service)) {
                    response.setContentType("application/x-git-receive-pack-advertisement");

                    // Required prefix
                    packetOut.writeString("# service=git-receive-pack\n");
                    packetOut.end();

                    ReceivePack rp = new ReceivePack(repository);
                    rp.setCheckReceivedObjects(true);
                    rp.setCheckReferencedObjectsAreReachable(true);
                    rp.setAllowCreates(true);
                    rp.setAllowDeletes(true);
                    rp.setAllowNonFastForwards(true);

                    rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(packetOut));

                } else {
                    log.warn("Unsupported service: {}", service);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported service: " + service);
                    return;
                }

                rawOut.flush();
                log.debug("Successfully sent advertisement for {}/{} service={}", username, repoName, service);
            }

        } catch (AsyncRequestNotUsableException e) {
            log.warn("Client disconnected during info/refs for {}/{}", username, repoName);
        } catch (IOException e) {
            log.error("IO error in handleInfoRefs for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        } catch (Exception e) {
            log.error("Unexpected error in handleInfoRefs for {}/{}: {}", username, repoName, e.getMessage(), e);
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

            response.setContentType("application/x-git-upload-pack-result");
            response.setHeader("Cache-Control", "no-cache");

            try (ServletInputStream in = request.getInputStream();
                 OutputStream out = response.getOutputStream()) {

                UploadPack up = new UploadPack(repository);
                up.upload(in, out, NullOutputStream.INSTANCE);

                out.flush();
            }

            log.debug("Successfully completed upload-pack for {}/{}", username, repoName);

        } catch (AsyncRequestNotUsableException e) {
            log.warn("Client disconnected during upload-pack for {}/{}", username, repoName);
        } catch (IOException e) {
            log.error("IO error in upload-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Upload failed");
                }
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        } catch (Exception e) {
            log.error("Unexpected error in upload-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
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

            response.setContentType("application/x-git-receive-pack-result");
            response.setHeader("Cache-Control", "no-cache");

            try (ServletInputStream in = request.getInputStream();
                 OutputStream out = response.getOutputStream()) {

                ReceivePack rp = new ReceivePack(repository);
                rp.setCheckReceivedObjects(true);
                rp.setCheckReferencedObjectsAreReachable(true);
                rp.setAllowCreates(true);
                rp.setAllowDeletes(true);
                rp.setAllowNonFastForwards(true);

                // Hooks for logging
                rp.setPreReceiveHook((receivePack, commands) -> {
                    log.debug("Pre-receive: {} commands for {}/{}", commands.size(), username, repoName);
                });
                rp.setPostReceiveHook((receivePack, commands) -> {
                    long ok = commands.stream().filter(c -> c.getResult() == ReceiveCommand.Result.OK).count();
                    log.info("Post-receive: {} successful commands for {}/{}", ok, username, repoName);
                });

                rp.receive(in, out, NullOutputStream.INSTANCE);

                out.flush();
            }

            log.debug("Successfully completed receive-pack for {}/{}", username, repoName);

        } catch (AsyncRequestNotUsableException e) {
            log.warn("Client disconnected during receive-pack for {}/{}", username, repoName);
        } catch (IOException e) {
            log.error("IO error in receive-pack for {}/{}: {}", username, repoName, e.getMessage(), e);
            // don’t send errors here: Git client may already be mid-protocol
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

