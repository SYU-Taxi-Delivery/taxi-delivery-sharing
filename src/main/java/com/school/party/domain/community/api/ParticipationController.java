package com.school.party.domain.community.api;

import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.security.CurrentUser;
import com.school.party.domain.community.service.ParticipationService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.net.URI;
import static com.school.party.domain.community.api.ApiModels.*;

@RestController
public class ParticipationController {
    private final ParticipationService service;
    private final CurrentUser currentUser;
    private final Clock clock;
    public ParticipationController(ParticipationService service, CurrentUser currentUser, Clock clock) { this.service = service; this.currentUser = currentUser; this.clock = clock; }
    @PostMapping("/api/posts/{postId}/participants")
    public ResponseEntity<Envelope<MutationView>> join(@PathVariable String postId, @RequestBody(required = false) String body, Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth); long id = id(postId); noBody(body);
        Result<MutationView> result = service.join(id, actor);
        if (result.data().action().equals("INITIAL_JOIN"))
            return ResponseEntity.created(URI.create("/api/posts/" + id + "/participants/me")).body(ApiResponses.success(result, req));
        return ResponseEntity.ok(ApiResponses.success(result, req));
    }
    @DeleteMapping("/api/posts/{postId}/participants")
    public Envelope<MutationView> cancel(@PathVariable String postId, @RequestBody(required = false) String body, Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth); long id = id(postId); noBody(body);
        return ApiResponses.success(service.cancel(id, actor), req);
    }
    @GetMapping("/api/posts/{postId}/participants")
    public Envelope<MembersView> members(@PathVariable String postId, Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.members(id(postId), currentUser.id(auth)), req);
    }
    @GetMapping("/api/posts/{postId}/participants/me")
    public Envelope<StateView> mine(@PathVariable String postId, Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.mine(id(postId), currentUser.id(auth)), req);
    }
    @GetMapping("/api/users/me/participations")
    public Envelope<HistoryView> history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status, Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.history(currentUser.id(auth), page, size, status), req);
    }
    private long id(String value) {
        try { if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new ApiException(ErrorCode.INVALID_POST_ID, clock.instant()); }
    }
    private void noBody(String body) { if (body != null && !body.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST, clock.instant()); }
}
