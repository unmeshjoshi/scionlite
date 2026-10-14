package com.versioncontrol.scion.topology;

/**
 * How long each tier waits for a reply, in ticks. <b>[Lite assumption]</b>: the post gives no timeouts. A tier
 * waits less than the tier that called it, so that a cache whose server does not answer can still try another
 * server before the daemon and scn give up.
 */
public final class Timeouts {
    /** A server waiting on one Spanner or cloud storage call. */
    public static final int SERVER = 100;
    /** A cache waiting on a whole server operation. */
    public static final int CACHE = 1000;
    public static final int DAEMON = 1300;
    public static final int SCN = 1600;

    private Timeouts() {
    }
}
