/**인증코드 검증 요청*/
package com.school.party.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class VerifyMailCodeRequest {

    @NotBlank(message = "학교 이메일은 필수 입력값입니다.")
    @Size(max = 120, message = "올바른 이메일 형식이 아닙니다.")
    private String schoolEmail;

    @NotBlank(message = "인증 코드는 필수 입력값입니다.")
    @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.")
    private String code;
}
