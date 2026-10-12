package com.school.party.domain.community.api;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Map;
@RestController
public class CsrfController {
    private final Clock clock;
    public CsrfController(Clock clock) { this.clock = clock; }
    @GetMapping("/api/csrf")
    public ApiModels.Envelope<Map<String,String>> csrf(CsrfToken token, HttpServletRequest request) {
        return ApiResponses.success(new ApiModels.Result<>(Map.of("headerName", token.getHeaderName(), "token", token.getToken()), clock.instant()), request);
    }
}
