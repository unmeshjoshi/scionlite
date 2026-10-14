package com.versioncontrol.scionlite.async;

import com.tickloom.future.TickCompletableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Combinators over tickloom's {@link TickCompletableFuture}. Two of its rules shape them: a pending future takes only
 * one callback, and a callback on a completed future runs at once, on the caller's stack.
 */
public final class Futures {
    private Futures() {
    }

    public static <T> TickCompletableFuture<T> failed(Throwable error) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        future.fail(error);
        return future;
    }

    /** Completes with every result, in order, or fails with the first failure. */
    public static <T> TickCompletableFuture<List<T>> allOf(List<TickCompletableFuture<T>> futures) {
        TickCompletableFuture<List<T>> all = new TickCompletableFuture<>();
        if (futures.isEmpty()) {
            all.complete(List.of());
            return all;
        }
        Object[] results = new Object[futures.size()];
        int[] remaining = {futures.size()};
        for (int i = 0; i < futures.size(); i++) {
            int index = i;
            futures.get(i).whenComplete((result, error) -> {
                if (!all.isPending()) {
                    return;
                }
                if (error != null) {
                    all.fail(error);
                    return;
                }
                results[index] = result;
                if (--remaining[0] == 0) {
                    @SuppressWarnings("unchecked")
                    List<T> list = (List<T>) new ArrayList<>(Arrays.asList(results));
                    all.complete(list);
                }
            });
        }
        return all;
    }

    /**
     * An asynchronous while loop: applies {@code step} to the state until {@code done} holds. Steps that complete at
     * once are run in a plain loop, so a long walk over cached data does not grow the stack.
     */
    public static <S> TickCompletableFuture<S> loop(S initial, Predicate<S> done,
                                                    Function<S, TickCompletableFuture<S>> step) {
        TickCompletableFuture<S> result = new TickCompletableFuture<>();
        new Object() {
            void drive(S state) {
                try {
                    while (!done.test(state)) {
                        TickCompletableFuture<S> next = step.apply(state);
                        if (next.isCompleted()) {
                            state = next.getResult();
                        } else if (next.isFailed()) {
                            result.fail(next.getException());
                            return;
                        } else {
                            next.whenComplete((value, error) -> {
                                if (error != null) {
                                    result.fail(error);
                                } else {
                                    drive(value);
                                }
                            });
                            return;
                        }
                    }
                    result.complete(state);
                } catch (RuntimeException e) {
                    result.fail(e);
                }
            }
        }.drive(initial);
        return result;
    }

    /** Runs {@code action} on each item in turn, waiting for one before starting the next. */
    public static <T> TickCompletableFuture<Void> forEachInTurn(List<T> items,
                                                               Function<T, TickCompletableFuture<?>> action) {
        return loop(0, i -> i >= items.size(), i -> action.apply(items.get(i)).thenApply(ignored -> i + 1))
                .thenApply(ignored -> null);
    }
}
