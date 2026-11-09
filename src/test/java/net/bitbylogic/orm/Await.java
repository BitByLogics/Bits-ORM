package net.bitbylogic.orm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bridges this library's callback-based async API into blocking calls for tests.
 */
public final class Await {

    private Await() {
    }

    public static <T> T value(Consumer<Consumer<T>> asyncCall) {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        asyncCall.accept(value -> {
            result.set(value);
            latch.countDown();
        });

        awaitLatch(latch);
        return result.get();
    }

    public static void run(Consumer<Runnable> asyncCall) {
        CountDownLatch latch = new CountDownLatch(1);
        asyncCall.accept(latch::countDown);
        awaitLatch(latch);
    }

    public static void until(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }

        fail(failureMessage);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("Timed out waiting for async operation to complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

}
