package com.school.party.domain.community.repository;

import com.school.party.domain.community.domain.Post;
import java.util.Optional;

public interface LockedPostRepository {
    /** Requires a write transaction. Every mutation must use this before inspecting aggregate state. */
    Optional<Post> findByIdForUpdate(long id);
}
