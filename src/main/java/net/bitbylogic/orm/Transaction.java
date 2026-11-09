package net.bitbylogic.orm;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A single JDBC connection with autoCommit disabled, shared across multiple statements so they
 * either all succeed or all roll back together. Obtained via {@link BormAPI#transaction}.
 */
public class Transaction {

    private final Connection connection;
    private final List<Runnable> afterCommitHooks = new ArrayList<>();
    private final List<Runnable> rollbackHooks = new ArrayList<>();

    Transaction(@NonNull Connection connection) {
        this.connection = connection;
    }

    public void execute(@NonNull String query, @Nullable Consumer<ResultSet> consumer, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS)) {
            bind(statement, args);
            statement.executeUpdate();

            try (ResultSet result = statement.getGeneratedKeys()) {
                if (consumer != null) {
                    consumer.accept(result);
                }
            }
        }
    }

    public void query(@NonNull String query, @NonNull Consumer<ResultSet> consumer, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            bind(statement, args);

            try (ResultSet result = statement.executeQuery()) {
                consumer.accept(result);
            }
        }
    }

    /**
     * Registers a callback to run only once this transaction actually commits - used for side
     * effects (like Redis change notifications) that must never fire for a write that later rolls back.
     */
    public void addAfterCommitHook(@NonNull Runnable hook) {
        afterCommitHooks.add(hook);
    }

    /**
     * Registers a callback to run if this transaction rolls back - used to undo in-memory changes
     * (like cache removals) that were made optimistically while the transaction was running.
     */
    public void addRollbackHook(@NonNull Runnable hook) {
        rollbackHooks.add(hook);
    }

    void runAfterCommitHooks(Logger logger) {
        runHooks(afterCommitHooks, logger);
    }

    void runRollbackHooks(Logger logger) {
        runHooks(rollbackHooks, logger);
    }

    private void runHooks(List<Runnable> hooks, Logger logger) {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Transaction hook failed", e);
            }
        }
    }

    private void bind(PreparedStatement statement, Object[] args) throws SQLException {
        if (args == null) {
            return;
        }

        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

}
