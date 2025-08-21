package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class RepositoryTreeDto {
    private String repoName;
    private boolean isPrivate;
    private FileTreeNode root;
}

