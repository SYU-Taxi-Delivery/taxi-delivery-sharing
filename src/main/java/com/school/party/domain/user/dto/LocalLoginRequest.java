package com.school.party.domain.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

@Getter
@NoArgsConstructor
public class LocalLoginRequest {
    @NotBlank private String identifier; // 학번 또는 이메일
    @NotBlank private String password;
}
