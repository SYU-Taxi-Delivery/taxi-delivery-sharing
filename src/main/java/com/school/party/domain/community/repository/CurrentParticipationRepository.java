package com.school.party.domain.community.repository;

import com.school.party.domain.community.domain.Participation;
import java.util.Optional;

public interface CurrentParticipationRepository {
    /** Call only after acquiring the parent POST write lock in a READ_COMMITTED write transaction. */
    Optional<Participation> findCurrentForMutation(long postId, long userId);
}
