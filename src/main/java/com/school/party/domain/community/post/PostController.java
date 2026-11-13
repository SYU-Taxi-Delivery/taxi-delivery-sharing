package com.school.party.domain.community.post;

import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.security.CurrentUser;
import com.school.party.domain.community.service.PostLifecycleService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Clock;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.post.PostResponse.*;

@RestController
@RequestMapping(value = "/api/posts", produces = MediaType.APPLICATION_JSON_VALUE)
public class PostController {
    private final PostService service;
    private final PostLifecycleService lifecycle;
    private final CurrentUser currentUser;
    private final Clock clock;
    public PostController(PostService service, PostLifecycleService lifecycle, CurrentUser currentUser, Clock clock) {
        this.service = service; this.lifecycle = lifecycle; this.currentUser = currentUser; this.clock = clock;
    }
    /** Author: close with the current members (현재 인원으로 모집 마감). Members and the link stay. */
    @PostMapping("/{postId}/close")
    public Envelope<Detail> close(@PathVariable String postId, @RequestBody(required = false) String body, Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth); long id = id(postId); noBody(body);
        lifecycle.close(id, actor);
        return ApiResponses.success(service.get(id, actor), req);
    }
    /** Author: cancel the whole schedule (일정 자체 취소). Every member is cancelled; cannot be undone. */
    @PostMapping("/{postId}/cancel")
    public Envelope<Detail> cancel(@PathVariable String postId, @RequestBody(required = false) String body, Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth); long id = id(postId); noBody(body);
        lifecycle.cancelRecruitment(id, actor);
        return ApiResponses.success(service.get(id, actor), req);
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
        @RequestParam(required = false) String q, @RequestParam(required = false) String departure,
        @RequestParam(required = false) String destination, @RequestParam(required = false) String restaurant,
        @RequestParam(required = false) String pickup, @RequestParam(required = false) String direction,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) Boolean joinable, @RequestParam(required = false) String sort,
        Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth);
        PostSearch search = PostSearch.parse(q, departure, destination, restaurant, pickup, direction, from, to, joinable, sort, clock.instant());
        return ApiResponses.success(service.list(actor, page, size, type, status, search), req);
    }
    private void noBody(String body) { if (body != null && !body.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST, clock.instant()); }
    private long id(String value) {
        try { if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new ApiException(ErrorCode.INVALID_POST_ID, clock.instant()); }
    }
}
