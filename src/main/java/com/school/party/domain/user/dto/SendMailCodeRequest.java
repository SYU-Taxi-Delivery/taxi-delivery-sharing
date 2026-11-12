/**인증코드 발송 요청*/
package com.school.party.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SendMailCodeRequest {

    /** 형식과 학교 도메인은 SchoolEmailPolicy가 공백 제거·소문자 변환 후 검사한다. */
    @NotBlank(message = "학교 이메일은 필수 입력값입니다.")
    @Size(max = 120, message = "올바른 이메일 형식이 아닙니다.")
    private String schoolEmail;
}
