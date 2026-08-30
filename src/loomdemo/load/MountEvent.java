package loomdemo.load;

/**
 * One thing that happened to one request, at one instant, on one carrier.
 *
 * <p>The Frame by Frame tab records these on both sides of every blocking call and derives
 * everything else from them — the lanes, the figures and the STEP lines. Nothing about the
 * board is stored twice, so nothing on screen can drift from the run it came from.
 *
 * <p>The carrier is read from {@link Thread#toString()}. A <em>mounted</em> virtual thread
 * prints as {@code VirtualThread[#23,req-0]/runnable@ForkJoinPool-1-worker-1} and a parked
 * one as {@code VirtualThread[#23,req-0]/timed_waiting} with no {@code @} at all, so the
 * carrier name is public information without touching a single internal API. Marks are only
 * ever taken from <em>inside</em> the request's own thread, where it is mounted by
 * definition, so {@link #carrier()} is never the unmounted case here.
 *
 * @param nanos       {@code System.nanoTime()} at the mark
 * @param request     which request, {@code 0..requests-1}
 * @param threadLabel the thread's {@code toString()} with the carrier suffix stripped, so it
 *                    identifies the request's thread rather than where it happened to be
 * @param carrier     the platform thread actually executing this — a {@code ForkJoinPool}
 *                    worker under virtual threads, the request's own thread under platform
 * @param kind        what the mark is
 * @param service     the service being called or returning; empty for {@link Kind#START}
 *                    and {@link Kind#END}
 */
public record MountEvent(long nanos, int request, String threadLabel, String carrier,
                         Kind kind, String service) {

    /**
     * The four marks. The pairing is what matters: the stretch after a {@link #CALL} is time
     * spent waiting, and the stretch after anything else is time spent mounted and running.
     */
    public enum Kind {
        /** The request got a thread and started running. */
        START,
        /** About to enter a blocking call. Everything until the matching RETURN is a wait. */
        CALL,
        /** The blocking call returned — and this is where the carrier may have changed. */
        RETURN,
        /** The request finished. */
        END
    }

    /** True when the stretch <em>after</em> this mark is time the request spent waiting. */
    public boolean startsWait() {
        return kind == Kind.CALL;
    }
}
