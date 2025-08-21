package org.os.gitbase.git.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@AllArgsConstructor
@NoArgsConstructor
public class CreateRepositoryDto {
    private String username;
    private String repoName;
    private boolean isPrivate;
    private String defaultBranch;
}
