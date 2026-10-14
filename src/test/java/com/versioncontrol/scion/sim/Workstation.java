package com.versioncontrol.scion.sim;

import com.versioncontrol.scion.client.ScionClient;
import com.versioncontrol.scion.repo.Repository;
import com.tickloom.ProcessId;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.util.Clock;

/** A developer's machine: a client process on the simulated network and a repository on its own simulated disk. */
public class Workstation {
    final ProcessId id;
    final ScionClient client;
    final SimulatedFileSystem disk;
    final Clock clock;
    Repository repo;

    Workstation(ProcessId id, ScionClient client, SimulatedFileSystem disk, Clock clock) {
        this.id = id;
        this.client = client;
        this.disk = disk;
        this.clock = clock;
    }

    public ProcessId id() {
        return id;
    }

    public ScionClient client() {
        return client;
    }

    public Repository repo() {
        return repo;
    }

    public SimulatedFileSystem disk() {
        return disk;
    }

    void tick() {
        disk.tick();
        if (repo != null) {
            repo.tick();
        }
    }
}
