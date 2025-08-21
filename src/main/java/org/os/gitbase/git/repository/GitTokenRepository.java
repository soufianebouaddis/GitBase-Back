package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.GitToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GitTokenRepository extends JpaRepository<GitToken, Long> {
}
