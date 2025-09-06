package org.os.gitbase.git.config;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

@Configuration
public class GitServletConfig {

    private static final String BASE_PATH = "./gitbase/repositories";
    // handle refinfo, receivepacks and uploadpacks
    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet() {
        System.out.println("=== Creating GitServlet Bean ===");
        GitServlet gitServlet = new GitServlet();
        System.out.println("calling git servlet");
        gitServlet.setRepositoryResolver((req, name) -> {
            System.out.println("=== GitServlet.repositoryResolver called ===");
            System.out.println(">> GitServlet resolving repo: " + name);
            System.out.println(">> Request URI: " + req.getRequestURI());
            System.out.println(">> Request Method: " + req.getMethod());

            File repoDir = new File(BASE_PATH, name);
            if (!repoDir.exists()) {
                try {
                    throw new IOException("Repository not found: " + name);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                return new FileRepositoryBuilder()
                        .setGitDir(repoDir)
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // mount all repositories into this endpoint -> /gitbase/* (refs etc)
        return new ServletRegistrationBean<>(gitServlet, "/gitbase/*");
    }
}
