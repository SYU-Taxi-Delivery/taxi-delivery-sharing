/**이메일 인증 후 회원가입 요청*/
package com.school.party.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SchoolRegisterRequest {

    @NotBlank(message = "학번은 필수 입력값입니다.")
    private String studentNo;

    @NotBlank(message = "이름은 필수 입력값입니다.")
    private String name;

    @NotBlank(message = "학교 이메일은 필수 입력값입니다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String schoolEmail;

    @NotBlank(message = "비밀번호는 필수 입력값입니다.")
    private String password;
}