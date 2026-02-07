package org.os.gitbase.git.entity;


import jakarta.persistence.*;
import org.os.gitbase.git.entity.enums.PullRequestStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "pull_requests")
public class PullRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_branch_id", nullable = false)
    private Branch sourceBranch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_branch_id", nullable = false)
    private Branch targetBranch;

    @Column(nullable = false)
    private String author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PullRequestStatus status; // OPEN, CLOSED, MERGED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime mergedAt;

    @Column
    private String mergedBy;

    // Getters and setters
}
