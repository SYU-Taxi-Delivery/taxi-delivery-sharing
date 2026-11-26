package com.school.party.domain.community.report;

import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.security.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.report.ReportService.*;

/** Report someone else's post (#53). Handling reports is an administrator task outside this API. */
@RestController
@RequestMapping(value = "/api/posts/{postId}/reports", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReportController {
    private final ReportService service;
    private final CurrentUser currentUser;
    private final Clock clock;
    public ReportController(ReportService service, CurrentUser currentUser, Clock clock) {
        this.service = service; this.currentUser = currentUser; this.clock = clock;
    }
    @PostMapping
    public ResponseEntity<Envelope<ReportView>> create(@PathVariable String postId, @RequestBody CreateReportRequest body,
                                                       Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth);
        long id;
        try { if (!postId.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); id = Long.parseLong(postId); }
        catch (NumberFormatException e) { throw new ApiException(ErrorCode.INVALID_POST_ID, clock.instant()); }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.success(service.report(id, actor, body), req));
    }
}
