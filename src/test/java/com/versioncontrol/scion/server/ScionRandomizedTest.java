package com.versioncontrol.scion.server;

import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.protocol.PushResponse;
import com.versioncontrol.scion.repo.MergeOutcome;
import com.versioncontrol.scion.repo.Repository;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.sim.Workstation;
import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static com.versioncontrol.scion.sim.ScionSimulation.ALPHA;
import static com.versioncontrol.scion.sim.ScionSimulation.BETA;
import static com.versioncontrol.scion.sim.ScionSimulation.GAMMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Several developers push to one branch at once over a lossy, slow network while a follower crashes and restarts.
 * Every push the server acknowledged must end up in the final history, and every replica must agree on it.
 */
class ScionRandomizedTest {
    private static final int ROUNDS = 8;
    private static final int MAX_ATTEMPTS = 20;

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 42, 1234, 99991})
    void acknowledgedPushesAreNeverLostAndReplicasConverge(long seed) throws Exception {
        try (ScionSimulation sim = ScionSimulation.threeReplicas(seed)) {
            Random random = new Random(seed);
            Workstation founder = sim.workstation("founder");
            founder.repo().writeFile("shared.txt", "start\n");
            founder.repo().add("shared.txt");
            sim.await(founder.repo().commit("start"));
            assertTrue(sim.await(founder.repo().push(founder.client())).succeeded());

            List<Workstation> devs = List.of(sim.cloneTo("ada"), sim.cloneTo("bob"), sim.cloneTo("cy"));
            List<ProcessId> everyone = new ArrayList<>(List.of(ALPHA, BETA, GAMMA));
            devs.forEach(d -> everyone.add(d.id()));
            for (ProcessId from : everyone) {
                for (ProcessId to : everyone) {
                    if (!from.equals(to)) {
                        sim.cluster().setPacketLoss(from, to, 0.1);
                        sim.cluster().setNetworkDelay(from, to, random.nextInt(4));
                    }
                }
            }

            Set<ObjectId> acknowledged = new HashSet<>();
            int crashRound = random.nextInt(ROUNDS);
            ProcessId victim = random.nextBoolean() ? BETA : GAMMA;
            for (int round = 0; round < ROUNDS; round++) {
                if (round == crashRound) {
                    sim.cluster().crashNode(victim);
                }
                if (round == crashRound + 2) {
                    sim.cluster().restartNode(victim);
                }
                for (Workstation dev : devs) {
                    Repository repo = dev.repo();
                    String own = dev.id().name() + ".txt";
                    repo.writeFile(own, repo.readFile(own).orElse("") + "round " + round + "\n");
                    repo.writeFile("shared.txt", repo.readFile("shared.txt").orElse("") + dev.id().name() + " " + round + "\n");
                    repo.addAll();
                    sim.await(repo.commit(dev.id().name() + " round " + round));
                }
                // Everyone pushes at once; the losers integrate and try again.
                List<Workstation> pending = new ArrayList<>(devs);
                for (int attempt = 0; !pending.isEmpty(); attempt++) {
                    assertTrue(attempt < MAX_ATTEMPTS, "pushes did not settle");
                    List<TickCompletableFuture<PushResponse>> pushes = pending.stream()
                            .map(d -> d.repo().push(d.client())).toList();
                    sim.tickUntil(() -> pushes.stream().noneMatch(TickCompletableFuture::isPending));
                    List<Workstation> retry = new ArrayList<>();
                    for (int i = 0; i < pending.size(); i++) {
                        Workstation dev = pending.get(i);
                        TickCompletableFuture<PushResponse> push = pushes.get(i);
                        if (push.isCompleted() && push.getResult().succeeded()) {
                            acknowledged.add(dev.repo().headCommit());
                        } else {
                            integrate(sim, dev);
                            retry.add(dev);
                        }
                    }
                    pending = retry;
                }
            }

            // Heal everything and let the dust settle.
            if (sim.server(victim).isStopped()) {
                sim.cluster().restartNode(victim);
            }
            for (ProcessId from : everyone) {
                for (ProcessId to : everyone) {
                    if (!from.equals(to)) {
                        sim.cluster().setPacketLoss(from, to, 0);
                    }
                }
            }
            ObjectId finalMain = sim.server(ALPHA).committedRefs().get("main");
            sim.tickUntil(() -> sim.servers().stream().allMatch(s -> s.isRunning()
                    && finalMain.equals(s.committedRefs().get("main"))
                    && s.logLength() == sim.server(ALPHA).logLength()));

            Workstation auditor = sim.cloneTo("auditor");
            Set<ObjectId> history = auditor.repo().log().stream().map(c -> c.id()).collect(Collectors.toSet());
            for (ObjectId commit : acknowledged) {
                assertTrue(history.contains(commit), "acknowledged commit " + commit + " lost");
            }
            String shared = auditor.repo().readFile("shared.txt").orElseThrow();
            for (Workstation dev : devs) {
                for (int round = 0; round < ROUNDS; round++) {
                    assertTrue(shared.contains(dev.id().name() + " " + round + "\n"),
                            "missing " + dev.id().name() + " " + round + " in\n" + shared);
                }
            }
            assertFalse(shared.contains("<<<<<<<"));
        }
    }

    // Pull until it gets through, then finish any conflicted merge by keeping both sides' lines.
    private static void integrate(ScionSimulation sim, Workstation dev) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            TickCompletableFuture<MergeOutcome> pull = dev.repo().pull(dev.client());
            sim.tickUntil(() -> !pull.isPending());
            if (pull.isFailed()) {
                continue;   // the fetch timed out on the lossy network
            }
            if (pull.getResult().kind() == MergeOutcome.Kind.CONFLICTS) {
                for (String path : pull.getResult().conflicts()) {
                    String resolved = dev.repo().readFile(path).orElseThrow().lines()
                            .filter(line -> !line.startsWith("<<<<<<<") && !line.startsWith("=======")
                                    && !line.startsWith(">>>>>>>"))
                            .map(line -> line + "\n")
                            .collect(Collectors.joining());
                    dev.repo().writeFile(path, resolved);
                    dev.repo().add(path);
                }
                sim.await(dev.repo().commit("Merge origin/main"));
            }
            return;
        }
        fail("could not pull");
    }
}
