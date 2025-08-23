package org.os.gitbase.security.config;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;


@Configuration
public class GitSmartHttpConfig {
    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet() {

        GitServlet gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver((req, repoName) -> {
            File baseDir = new File("./gitbase/repositories");
            File gitDir = new File(baseDir, repoName);
            if (!gitDir.exists() || !gitDir.isDirectory()) {
                throw new RepositoryNotFoundException(repoName);
            }
            try {
                return new FileRepositoryBuilder()
                        .setGitDir(gitDir)
                        .setBare()
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return new ServletRegistrationBean<>(gitServlet, "/api/v1/gitbase/**");

    }
}
