package loomdemo.load;

/**
 * One instant at which a virtual thread was found waiting <em>and still holding a carrier</em>.
 *
 * <p>That conjunction is the whole evidence for pinning, and it is why this is sampled rather
 * than assumed. A virtual thread that unmounted normally is {@code TIMED_WAITING} with no
 * carrier in its {@code toString()}; one that is merely running is {@code RUNNABLE}. Only a
 * pinned thread is both waiting and mounted at the same instant, so a single sample of that
 * shape proves the pin, and no samples at all prove there was none.
 *
 * <p>Recorded by the sweep in {@link FrameRecorder}, from outside the thread, out of the same
 * public {@code Thread.toString()} the carrier lanes are already built from. No agent, no JFR
 * and no {@code jdk.internal} — and no flag telling the run what to conclude about itself.
 *
 * @param carrier the platform thread the waiting virtual thread was still sitting on
 */
public record PinSample(long nanos, int request, String carrier) {
}
