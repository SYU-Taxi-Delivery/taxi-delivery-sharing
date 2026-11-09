/**이메일 인증 후 회원가입 요청*/
package com.school.party.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SchoolRegisterRequest {

    @NotBlank(message = "학번은 필수 입력값입니다.")
    @Size(max = 20, message = "학번은 20자 이하입니다.")
    private String studentNo;

    @NotBlank(message = "이름은 필수 입력값입니다.")
    @Size(max = 50, message = "이름은 50자 이하입니다.")
    private String name;

    /** 형식과 학교 도메인은 SchoolEmailPolicy가 공백 제거·소문자 변환 후 검사한다. */
    @NotBlank(message = "학교 이메일은 필수 입력값입니다.")
    @Size(max = 120, message = "올바른 이메일 형식이 아닙니다.")
    private String schoolEmail;

    @NotBlank(message = "비밀번호는 필수 입력값입니다.")
    @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하입니다.")
    private String password;
}
