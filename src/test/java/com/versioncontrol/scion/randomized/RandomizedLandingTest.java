package com.versioncontrol.scion.randomized;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.sim.ScionSimulation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several workstations in two regions commit and land at the same time while every link between tiers drops
 * packets. A land whose reply is lost may or may not have happened, so the test checks only what was reported:
 * every changeset reported landed is in main's history, and main ends with each workstation's last landed file.
 */
class RandomizedLandingTest {
    private static final List<String> WORKSTATIONS = List.of("ada", "bob", "cy", "dee");
    private static final int ROUNDS = 3;
    private static final double LOSS = 0.05;

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, })
    void everyReportedLandingIsInMain(long seed) throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(3)
                .region("west", "ada", "bob").region("east", "cy", "dee").build(seed)) {
            Map<String, Scn> scns = new LinkedHashMap<>();
            for (String ws : WORKSTATIONS) {
                scns.put(ws, sim.scn(ws));
            }
            List<ProcessId> nodes = sim.topology().allNodes();
            for (ProcessId from : nodes) {
                for (ProcessId to : nodes) {
                    if (!from.equals(to)) {
                        sim.cluster().setPacketLoss(from, to, LOSS);
                    }
                }
            }

            Set<String> reportedLanded = new HashSet<>();
            Map<String, String> lastLandedContent = new LinkedHashMap<>();
            for (int round = 1; round <= ROUNDS; round++) {
                Map<String, String> content = new LinkedHashMap<>();
                List<TickCompletableFuture<String>> commits = new ArrayList<>();
                for (String ws : WORKSTATIONS) {
                    content.put(ws, ws + " round " + round + "\n");
                    scns.get(ws).write(ws + ".txt", content.get(ws));
                    Scn scn = scns.get(ws);
                    String message = ws + " round " + round;
                    commits.add(retry(() -> scn.commit(message), 10));
                }
                commits.forEach(sim::await);

                Map<String, TickCompletableFuture<String>> lands = new LinkedHashMap<>();
                WORKSTATIONS.forEach(ws -> lands.put(ws, scns.get(ws).land("main")));
                sim.tickUntil(() -> lands.values().stream().noneMatch(TickCompletableFuture::isPending));
                lands.forEach((ws, land) -> {
                    if (land.isCompleted()) {
                        reportedLanded.add(land.getResult());
                        lastLandedContent.put(ws, content.get(ws));
                    } else {
                        assertFalse(land.getException() instanceof Scn.LandConflictException,
                                "different files never conflict: " + land.getException());
                    }
                });
            }

            sim.cluster().resetNetworkConditions();
            Scn ada = scns.get("ada");
            String main = sim.await(ada.resolveRef("main"));
            List<String> history = sim.await(ada.log(main, 0));
            assertTrue(history.containsAll(reportedLanded), "landed changesets missing from main");
            assertFalse(reportedLanded.isEmpty(), "nothing landed at 5% loss");
            Map<String, String> files = sim.await(ada.readAllFiles(main));
            // A later land that did happen but whose reply was lost can only make a file newer, never older.
            lastLandedContent.forEach((ws, text) -> {
                String actual = files.get(ws + ".txt");
                assertNotNull(actual, ws + "'s file is missing from main");
                assertTrue(round(actual) >= round(text), ws + ": main has " + actual + " but " + text + " landed");
            });
        }
    }

    private static int round(String text) {
        return Integer.parseInt(text.trim().replaceAll(".* round ", ""));
    }

    /** Retries a call until it succeeds, at most {@code attempts} times. */
    private static <T> TickCompletableFuture<T> retry(Supplier<TickCompletableFuture<T>> call, int attempts) {
        TickCompletableFuture<T> result = new TickCompletableFuture<>();
        call.get().whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else if (attempts <= 1) {
                result.fail(error);
            } else {
                retry(call, attempts - 1).whenComplete((v, e) -> {
                    if (e == null) {
                        result.complete(v);
                    } else {
                        result.fail(e);
                    }
                });
            }
        });
        return result;
    }
}
