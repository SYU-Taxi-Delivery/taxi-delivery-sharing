package com.school.party.domain.community.post;

import jakarta.servlet.http.HttpServletRequest;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.post.PostResponse.*;

/** "내 작성 글": the signed-in author's own posts, same item shape as the list. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class MyPostController {
    private final PostService service;
    private final CurrentUser currentUser;
    public MyPostController(PostService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping("/api/users/me/posts")
    public Envelope<PageView> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        Authentication auth, HttpServletRequest req) {
        return ApiResponses.success(service.mine(currentUser.id(auth), page, size), req);
    }
}
