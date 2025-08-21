package org.os.gitbase.git.service;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.eclipse.jgit.transport.BasePackPushConnection.*;
@Service
public class CommandGitService  {
    private final GitTokenRepository repo;
    private final PasswordEncoder passwordEncoder;
    private static final String BASE_PATH = "gitbase/repositories"; // root path
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
            response.setContentType("application/x-" + service + "-advertisement");
            response.setStatus(HttpServletResponse.SC_OK);

            OutputStream out = response.getOutputStream();

            // Write header in packet line format
            String header = "# service=" + service + "\n";
            writePacket(out, header);
            writeFlush(out);

            if ("git-upload-pack".equals(service)) {
                UploadPack uploadPack = new UploadPack(repository);
                RefAdvertiser.PacketLineOutRefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                uploadPack.sendAdvertisedRefs(adv);
            } else if ("git-receive-pack".equals(service)) {
                ReceivePack receivePack = new ReceivePack(repository);
                RefAdvertiser.PacketLineOutRefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                receivePack.sendAdvertisedRefs(adv);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Git fetch/clone (POST /git-upload-pack) ---
    public void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request, HttpServletResponse response) {
        try (Repository repository = openRepository(username, repoName)) {
            response.setContentType("application/x-git-upload-pack-result");
            UploadPack uploadPack = new UploadPack(repository);
            uploadPack.upload(request.getInputStream(), response.getOutputStream(), System.err);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Git push (POST /git-receive-pack) ---
    public void handleReceivePack(String username, String repoName,
                                  HttpServletRequest request, HttpServletResponse response) {
        try (Repository repository = openRepository(username, repoName)) {
            response.setContentType("application/x-git-receive-pack-result");
            ReceivePack receivePack = new ReceivePack(repository);
            receivePack.receive(request.getInputStream(), response.getOutputStream(), System.err);
        } catch (IOException e) {
            throw new RuntimeException(e);
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

    public boolean validate(User user, String rawToken) {
        return user.getGitTokens().stream()
                .anyMatch(t -> passwordEncoder.matches(rawToken, t.getTokenHash())
                        && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now())));
    }


}

