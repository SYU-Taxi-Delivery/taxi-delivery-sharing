package com.school.party.domain.community.repository;

import jakarta.persistence.EntityManager;
import org.hibernate.engine.spi.SessionImplementor;

/**
 * Tells whether the current persistence context already holds an entity, so the locking
 * repositories refresh only instances that were loaded before the lock (and may be stale).
 * An entity first loaded by the locking query already carries committed state, and refreshing
 * it would only repeat the same SELECT.
 */
final class ManagedEntities {
    private ManagedEntities() {}

    static boolean contains(EntityManager entityManager, Class<?> type, Object id) {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        var persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(type);
        return session.getPersistenceContextInternal().containsEntity(session.generateEntityKey(id, persister));
    }

    static boolean containsAny(EntityManager entityManager, Class<?> type) {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        for (var entry : session.getPersistenceContextInternal().reentrantSafeEntityEntries())
            if (type.isInstance(entry.getKey())) return true;
        return false;
    }
}
