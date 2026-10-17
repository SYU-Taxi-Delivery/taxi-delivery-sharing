package com.school.party.domain.community.repository;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import org.hibernate.Session;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Validate the existing transaction too: REQUIRED does not override an outer transaction's isolation. */
public final class MutationTransactions {
    private MutationTransactions() {}
    public static void requireWritableReadCommitted(EntityManager entityManager) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Community mutations require a writable transaction");
        int isolation = entityManager.unwrap(Session.class).doReturningWork(Connection::getTransactionIsolation);
        if (isolation != Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalTransactionStateException("Community mutations require READ_COMMITTED, including an existing outer transaction");
    }
}
