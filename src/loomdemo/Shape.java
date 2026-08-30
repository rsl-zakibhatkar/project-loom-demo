package loomdemo;

/**
 * What one Frame by Frame run is made of: which kind of thread carries a request, and what
 * that request is holding while it waits.
 *
 * <p>Deliberately not a fourth {@link Era}. An era is a way a whole server was written, and
 * the Performance Comparison tab switches between three of them; this is one axis finer —
 * {@link #VIRTUAL} and {@link #PINNED} are the <em>same</em> era, the same executor and the
 * same three service calls, differing only in one keyword around them. Folding that into
 * {@code Era} would put a fourth panel on a tab that measures eras, and would make
 * {@code OrderServer.startFor}'s exhaustive switch answer a question nobody asked it.
 *
 * <p>{@link #era()} is what the colours and the existing panels read, so {@code MountBoard},
 * {@code TraceView} and every {@code .stats-panel.future} rule keep working untouched — the
 * same split {@code Era} already documents between stage vocabulary and internal identity.
 * The one exception is {@link #styleClass()}: pinning gets its own {@code pinned} class,
 * because red has to mean exactly one thing on this board.
 */
public enum Shape {

    PLATFORM(Era.PAST, false, "platform threads", "platform", "past"),

    VIRTUAL(Era.PRESENT, false, "virtual threads", "virtual", "future"),

    /**
     * Virtual threads whose blocking calls happen inside {@code synchronized}.
     *
     * <p>Every request takes <strong>its own</strong> monitor, so nothing ever contends and
     * the stall this produces cannot be explained away as lock contention. What is left is
     * the thing slide 40 claims: on JDK 21 a virtual thread inside a monitor cannot unmount,
     * so it keeps its carrier for the whole wait.
     */
    PINNED(Era.PRESENT, true, "virtual + synchronized", "virtual+sync", "pinned");

    private final Era era;
    private final boolean guarded;
    private final String label;
    private final String shortLabel;
    private final String styleClass;

    Shape(Era era, boolean guarded, String label, String shortLabel, String styleClass) {
        this.era = era;
        this.guarded = guarded;
        this.label = label;
        this.shortLabel = shortLabel;
        this.styleClass = styleClass;
    }

    /** Which era's colours and vocabulary this run wears. */
    public Era era() {
        return era;
    }

    /** Whether the three blocking calls happen inside a monitor this request owns alone. */
    public boolean guarded() {
        return guarded;
    }

    /** Picker button text. */
    public String label() {
        return label;
    }

    /** Short form for the run divider in the step log. */
    public String shortLabel() {
        return shortLabel;
    }

    /** {@code past} / {@code future} / {@code pinned}. */
    public String styleClass() {
        return styleClass;
    }

    /** The word after the shape in a stack panel's header. */
    public String stackRole() {
        return switch (this) {
            case PLATFORM -> "platform thread, waiting";
            case VIRTUAL -> "virtual thread, waiting";
            case PINNED -> "virtual thread, PINNED";
        };
    }
}
