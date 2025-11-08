package org.os.gitbase.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private String defaultBranch;
}
