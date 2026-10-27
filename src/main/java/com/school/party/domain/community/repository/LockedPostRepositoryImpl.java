package com.school.party.domain.community.repository;

import com.school.party.domain.community.domain.Post;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

public class LockedPostRepositoryImpl implements LockedPostRepository {
    @PersistenceContext private EntityManager entityManager;

    @Override public Optional<Post> findByIdForUpdate(long id) {
        MutationTransactions.requireWritableReadCommitted(entityManager);
        // Keep query auto-flush semantics and the existing POST row write lock.
        Optional<Post> post = entityManager.createQuery("select p from Post p where p.id = :id", Post.class)
            .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
        // A locking query alone may return an entity already present in the first-level cache.
        // Rehydrate while still holding the lock so callers always validate committed DB state.
        post.ifPresent(p -> entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE));
        return post;
    }
}
