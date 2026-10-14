package com.versioncontrol.scion.server;

import com.tickloom.Process;
import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;

import java.util.List;

/** For tickloom's server launcher: {@code --factory com.versioncontrol.scion.server.ScionServerFactory}. */
public class ScionServerFactory implements ProcessFactory {
    @Override
    public Process create(List<ProcessId> peerIds, ProcessParams processParams) {
        return new ScionServer(peerIds, processParams);
    }
}
