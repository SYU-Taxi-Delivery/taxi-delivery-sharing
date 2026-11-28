package com.school.party.domain.community.comment;

import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.security.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Clock;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.comment.CommentService.*;

/** Public comments on a post (#53). Every signed-in user may read and write; only the writer deletes. */
@RestController
@RequestMapping(value = "/api/posts/{postId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
public class CommentController {
    private final CommentService service;
    private final CurrentUser currentUser;
    private final Clock clock;
    public CommentController(CommentService service, CurrentUser currentUser, Clock clock) {
        this.service = service; this.currentUser = currentUser; this.clock = clock;
    }
    @GetMapping
    public Envelope<CommentList> list(@PathVariable String postId, Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth);
        return ApiResponses.success(service.list(id(postId, ErrorCode.INVALID_POST_ID), actor), req);
    }
    @PostMapping
    public ResponseEntity<Envelope<CommentView>> create(@PathVariable String postId, @RequestBody CreateCommentRequest body,
                                                        Authentication auth, HttpServletRequest req) {
        long actor = currentUser.id(auth); long id = id(postId, ErrorCode.INVALID_POST_ID);
        Result<CommentView> result = service.create(id, actor, body.content());
        return ResponseEntity.created(URI.create("/api/posts/" + id + "/comments/" + result.data().commentId()))
            .body(ApiResponses.success(result, req));
    }
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable String postId, @PathVariable String commentId,
                                       @RequestBody(required = false) String body, Authentication auth) {
        long actor = currentUser.id(auth);
        long post = id(postId, ErrorCode.INVALID_POST_ID), comment = id(commentId, ErrorCode.INVALID_COMMENT_ID);
        if (body != null && !body.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST, clock.instant());
        service.delete(post, comment, actor);
        return ResponseEntity.noContent().build();
    }
    private long id(String value, ErrorCode invalid) {
        try { if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new ApiException(invalid, clock.instant()); }
    }
}
