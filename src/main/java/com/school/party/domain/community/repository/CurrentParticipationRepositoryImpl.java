package com.school.party.domain.community.repository;

import com.school.party.domain.community.domain.Participation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

public class CurrentParticipationRepositoryImpl implements CurrentParticipationRepository {
    @PersistenceContext private EntityManager entityManager;

    @Override public Optional<Participation> findCurrentForMutation(long postId, long userId) {
        MutationTransactions.requireWritableReadCommitted(entityManager);
        // Only a participation loaded before the parent lock can be stale.
        boolean loadedBefore = ManagedEntities.containsAny(entityManager, Participation.class);
        Optional<Participation> participation = entityManager.createQuery(
                "select p from Participation p where p.post.id = :postId and p.user.id = :userId", Participation.class)
            .setParameter("postId", postId).setParameter("userId", userId).getResultList().stream().findFirst();
        // The held parent lock serializes all participation writers; refresh stale managed membership too.
        if (loadedBefore) participation.ifPresent(entityManager::refresh);
        return participation;
    }
}
