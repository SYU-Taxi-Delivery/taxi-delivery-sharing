package com.school.party.domain.community.post;

import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.security.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Clock;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.post.PostResponse.*;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final PostService service;
    private final CurrentUser currentUser;
    private final Clock clock;
    public PostController(PostService service, CurrentUser currentUser, Clock clock) {
        this.service = service; this.currentUser = currentUser; this.clock = clock;
    }
    @PostMapping
    public ResponseEntity<Envelope<Detail>> create(@RequestBody CreatePostRequest body, Authentication auth, HttpServletRequest req) {
        Result<Detail> result = service.create(currentUser.id(auth), body);
        return ResponseEntity.created(URI.create("/api/posts/" + result.data().postId())).body(ApiResponses.success(result, req));
    }
    @PatchMapping("/{postId}")
    public Envelope<Detail> update(@PathVariable String postId, @RequestBody JsonNode body, Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.update(id(postId), currentUser.id(auth), new UpdatePostRequest(body)), req);
    }
    @GetMapping("/{postId}")
    public Envelope<Detail> detail(@PathVariable String postId, Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.get(id(postId), currentUser.id(auth)), req);
    }
    @GetMapping
    public Envelope<PageView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String type, @RequestParam(required = false) String status,
        Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.list(currentUser.id(auth), page, size, type, status), req);
    }
    private long id(String value) {
        try { if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new ApiException(ErrorCode.INVALID_POST_ID, clock.instant()); }
    }
}
