package com.jamc.worship.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Runs blocking work (database, file I/O) off the event dispatch thread and delivers the
 * result back on it. Keeps the operator UI responsive during a live service.
 */
public final class BackgroundTask {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTask.class);

    private BackgroundTask() {
    }

    public static <T> SwingWorker<T, Void> run(String description, Callable<T> work,
                                               Consumer<T> onSuccess, Consumer<Throwable> onError) {
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                T result;
                try {
                    result = get();
                } catch (CancellationException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("Background task failed: {}", description, cause);
                    if (onError != null) {
                        onError.accept(cause);
                    }
                    return;
                }
                if (onSuccess != null) {
                    try {
                        onSuccess.accept(result);
                    } catch (RuntimeException e) {
                        log.error("Failed to apply result of: {}", description, e);
                        if (onError != null) {
                            onError.accept(e);
                        }
                    }
                }
            }
        };
        worker.execute();
        return worker;
    }
}
