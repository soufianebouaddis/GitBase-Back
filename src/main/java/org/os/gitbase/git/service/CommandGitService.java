package org.os.gitbase.git.service;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    // --- Git advertise refs (GET /info/refs?service=git-upload-pack) ---
    public void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response) {
        try (Repository repository = openRepository(username, repoName)) {
            OutputStream rawOut = response.getOutputStream();

            if ("git-upload-pack".equals(service)) {
                response.setContentType("application/x-git-upload-pack-advertisement");

                // 1. Write the service header
                PacketLineOut plo = new PacketLineOut(rawOut);
                plo.writeString("# service=" + service + "\n");
                plo.end(); // flush (0000)

                // 2. Advertise refs
                UploadPack up = new UploadPack(repository);
                RefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(new PacketLineOut(rawOut));
                up.sendAdvertisedRefs(adv);

            } else if ("git-receive-pack".equals(service)) {
                response.setContentType("application/x-git-receive-pack-advertisement");

                // 1. Write the service header
                PacketLineOut plo = new PacketLineOut(rawOut);
                plo.writeString("# service=" + service + "\n");
                plo.end();

                // 2. Advertise refs
                ReceivePack rp = new ReceivePack(repository);
                RefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(new PacketLineOut(rawOut));
                rp.sendAdvertisedRefs(adv);

            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported service: " + service);
                return;
            }

            rawOut.flush();
        } catch (IOException e) {
            throw new RuntimeException("Error in handleInfoRefs", e);
        }
    }



    // --- Git fetch/clone (POST /git-upload-pack) ---
    public void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request, HttpServletResponse response) {
        try (Repository repository = openRepository(username, repoName)) {
            response.setContentType("application/x-git-upload-pack-result");
            UploadPack uploadPack = new UploadPack(repository);

            // Stream only to response, avoid System.err
            uploadPack.upload(request.getInputStream(), response.getOutputStream(), response.getOutputStream());

        } catch (IOException e) {
            throw new RuntimeException("Error in handleUploadPack", e);
        }
    }

    // --- Git push (POST /git-receive-pack) ---
    public void handleReceivePack(String username, String repoName,
                                  HttpServletRequest request, HttpServletResponse response) {
        try (Repository repository = openRepository(username, repoName)) {
            response.setContentType("application/x-git-receive-pack-result");
            ReceivePack receivePack = new ReceivePack(repository);

            // Stream only to response, avoid System.err
            receivePack.receive(request.getInputStream(), response.getOutputStream(), response.getOutputStream());

        } catch (IOException e) {
            throw new RuntimeException("Error in handleReceivePack", e);
        }
    }


    // --- Utility: write Git packet line format ---
    private void writePacket(OutputStream out, String data) throws IOException {
        String packet = String.format("%04x%s", data.length() + 4, data);
        out.write(packet.getBytes());
    }

    private void writeFlush(OutputStream out) throws IOException {
        out.write("0000".getBytes());
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

