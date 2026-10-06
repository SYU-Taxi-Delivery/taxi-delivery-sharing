package com.school.party.domain.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LocalLoginRequest {
    private String identifier; // 학번 또는 이메일
    private String password;
}