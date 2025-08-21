package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface GitRepositoryDB extends JpaRepository<RepositoryGit, UUID> {
    List<RepositoryGit> findByOwner(User owner);
    Optional<RepositoryGit> findByOwnerNameAndRepoName(String ownerName, String repoName);

}
