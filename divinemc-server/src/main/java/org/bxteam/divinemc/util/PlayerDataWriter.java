package org.bxteam.divinemc.util;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bxteam.divinemc.config.DivineConfig;
import org.slf4j.Logger;

/**
 * SkyHand: writes player files (.dat, advancements, stats) on one background thread.
 * The caller builds the data on the main thread; only compression and disk writes move here.
 * One thread, so writes run in submission order. A read of a file waits for its pending write.
 */
public final class PlayerDataWriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Path, CompletableFuture<Void>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DivineMC Player Data Writer");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean stopped = false;

    @FunctionalInterface
    public interface Write {
        void run() throws Exception;
    }

    private PlayerDataWriter() {
    }

    /** Runs {@code write} on the writer thread, or right away when disabled or stopped. */
    public static void submit(final Path file, final String what, final Write write) {
        if (stopped || !DivineConfig.PerformanceCategory.asyncPlayerDataWrites) {
            await(file);
            runLogged(file, what, write);
            return;
        }
        final Path key = file.toAbsolutePath().normalize();
        final CompletableFuture<Void> future = new CompletableFuture<>();
        PENDING.put(key, future);
        EXECUTOR.execute(() -> {
            try {
                runLogged(file, what, write);
            } finally {
                future.complete(null);
                PENDING.remove(key, future);
            }
        });
    }

    /** Waits until no write of {@code file} is pending, so a read sees the last saved data. */
    public static void await(final Path file) {
        if (PENDING.isEmpty()) return;
        final CompletableFuture<Void> future = PENDING.get(file.toAbsolutePath().normalize());
        if (future != null) future.join();
    }

    /** Runs every queued write, then makes later writes synchronous. Called when the server stops. */
    public static void flush() {
        stopped = true;
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(60, TimeUnit.SECONDS)) {
                LOGGER.error("Player data writes still running after 60 s at shutdown ({} files)", PENDING.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runLogged(final Path file, final String what, final Write write) {
        try {
            write.run();
        } catch (Exception e) {
            LOGGER.error("Couldn't save {} to {}", what, file, e);
        }
    }
}
