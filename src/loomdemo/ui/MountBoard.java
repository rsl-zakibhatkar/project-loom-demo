package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.Era;
import loomdemo.load.FrameRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mount timeline: what every request was doing, above what every OS thread was doing.
 *
 * <p>Two groups of {@link MountLane}s drawn against one shared time window, so a gap in a
 * request row and a block in a carrier row that line up vertically really did happen at the
 * same instant. The top half is the story the code tells — three calls, three waits. The
 * bottom half is what the machine actually did about it, and under virtual threads it is a
 * few threads with almost nothing on them.
 *
 * <p>The carrier rows are an inversion of the request rows rather than a second measurement:
 * a carrier was busy exactly when some request was mounted on it. That is complete here
 * because every request in the run is one of ours.
 */
public final class MountBoard {

    /** Past this many requests the rows get short, or twenty-four of them fill a projector. */
    private static final int DENSE_ABOVE = 8;

    private static final String HEALTH_TOOLTIP =
            "One extra virtual thread, submitted after every request above.\n"
                    + "It takes no lock and does no work at all, so the only thing between it "
                    + "and finishing is getting a carrier.\n"
                    + "Think of it as the health check, or the request that would have been "
                    + "served instantly if anything had been free.";

    private final Label requestsHeading = new Label();
    private final Label carriersHeading = new Label();
    private final VBox requestRows = new VBox();
    private final VBox carrierRows = new VBox();
    private final Label caption = new Label();
    private final VBox node;

    private final List<MountLane> lanes = new ArrayList<>();

    public MountBoard() {
        requestsHeading.getStyleClass().add("section-label");
        carriersHeading.getStyleClass().add("section-label");

        requestRows.getStyleClass().add("lane-group");
        carrierRows.getStyleClass().add("lane-group");

        caption.getStyleClass().add("caption");
        caption.setWrapText(true);
        Tooltip.install(caption, new Tooltip(
                "Each request is marked on both sides of every blocking call, so what you are "
                        + "looking at is eight measured instants per request rather than a "
                        + "continuous trace.\nA mount or unmount happening between two marks "
                        + "would not appear here; at these latencies the JDK makes exactly the "
                        + "transitions that are marked — one per blocking call."));

        Region separator = new Region();
        separator.getStyleClass().add("panel-separator");

        node = new VBox(6, requestsHeading, requestRows, separator, carriersHeading,
                carrierRows, caption);
        node.getStyleClass().add("mount-board");
        clear();
    }

    public void clear() {
        lanes.clear();
        requestRows.getChildren().clear();
        carrierRows.getChildren().clear();
        carrierRows.setVisible(true);
        carrierRows.setManaged(true);
        requestsHeading.setText("Requests");
        carriersHeading.setText("OS threads");
        caption.setText("Run to record a timeline.");
    }

    public void setRun(FrameRun run) {
        clear();
        if (run.isEmpty()) {
            return;
        }
        String eraClass = run.era().styleClass();
        boolean dense = run.requestIds().size() > DENSE_ABOVE;
        long t0 = run.t0();
        long total = run.totalNanos();
        int focus = run.focusRequest();

        requestsHeading.setText(String.format(Locale.US,
                "Requests  —  %s, one row each,  %s ms of wall clock",
                run.era() == Era.PRESENT ? "virtual threads" : "platform threads",
                FrameRun.millis(total)));

        for (int request : run.requestIds()) {
            MountLane lane = newLane(dense);
            lane.setWindow(t0, total);
            String threadLabel = threadLabelOf(run, request);
            long waiting = 0;
            long mounted = 0;
            long pinned = 0;
            for (FrameRun.Span span : run.spansFor(request)) {
                if (span.mounted()) {
                    mounted += span.durationNanos();
                    lane.addBlock(span.startNanos(), span.endNanos(),
                            MountLane.describe(span), "lane-mounted", eraClass);
                } else {
                    waiting += span.durationNanos();
                    if (span.pinned()) {
                        pinned += span.durationNanos();
                    }
                    // A pinned wait is not the same fact as a wait. The request is idle either
                    // way; the difference is that this one took an OS thread down with it, and
                    // the row has to be able to say so without the figure being read.
                    lane.addBlock(span.startNanos(), span.endNanos(),
                            MountLane.describe(span),
                            span.pinned() ? "lane-pinned" : "lane-parked", eraClass);
                }
            }
            String name = "req-" + request;
            String figure;
            if (pinned > 0) {
                // Deliberately the same shape as the line below it — "ran X of Y" against
                // "HELD X of Y" in the same column is the whole comparison, and it has to fit
                // the same fixed width to stay readable.
                figure = String.format(Locale.US, "1 carrier · HELD %s of %s ms",
                        FrameRun.millis(mounted + pinned),
                        FrameRun.millis(mounted + waiting));
            } else if (run.era() == Era.PRESENT) {
                figure = String.format(Locale.US, "%d carriers · ran %s of %s ms",
                        run.hops(request),
                        FrameRun.millis(mounted), FrameRun.millis(mounted + waiting));
            } else {
                figure = String.format(Locale.US, "1 thread · held %s ms, %s waiting",
                        FrameRun.millis(mounted + waiting), FrameRun.millis(waiting));
            }
            requestRows.getChildren().add(
                    row(name, threadLabel, lane, figure, request == focus ? "focus" : null));
        }

        addProbeRow(run, dense, t0, total);

        // One thread each means the lanes below would be a row-for-row copy of the lanes
        // above. Say that, rather than drawing the same twenty-four rows twice.
        boolean onePerRequest = run.threadPerRequest();
        carrierRows.setVisible(!onePerRequest);
        carrierRows.setManaged(!onePerRequest);
        if (onePerRequest) {
            // A pinned run can land here honestly: at one request per carrier every virtual
            // thread holds one carrier end to end, which IS thread-per-request. Saying that
            // out loud is better than hiding it — it is the same picture, arrived at by a
            // thread model that was supposed to have made it impossible.
            carriersHeading.setText(run.pinned()
                    ? String.format(Locale.US,
                            "OS threads  —  %d of them, and every one is pinned: the rows "
                                    + "above ARE the carriers. Virtual threads just became "
                                    + "platform threads.", run.carriers().size())
                    : String.format(Locale.US,
                            "OS threads  —  %d of them, and the rows above ARE the threads: "
                                    + "one each, held from the first call to the last. That "
                                    + "is the whole problem.", run.carriers().size()));
            String pinnedCaption = run.probe() != null && run.probe().starved()
                    ? "Every request got a carrier and never let go of it, and every one of "
                            + "them still finished on time. Now read the health row: the one "
                            + "request that asked for nothing waited out the entire run, "
                            + "because there was no carrier left to give it."
                    : "There were still carriers to spare at this size, so the pin has cost "
                            + "nothing yet — which is exactly why this bug reaches production. "
                            + "Run it again with more requests than the machine has carriers.";
            caption.setText(run.pinned()
                    ? pinnedCaption
                    : "Every block above is an operating-system thread that existed, and was "
                            + "parked in the kernel, for as long as the request took.");
            return;
        }
        carriersHeading.setText(run.pinned()
                ? String.format(Locale.US,
                        "OS threads  —  %d of them, and %d are PINNED: a request is asleep on "
                                + "them and cannot be moved off", run.carriers().size(),
                        run.pinnedCarriers().size())
                : String.format(Locale.US,
                        "OS threads  —  %d of them, and this is everything they did",
                        run.carriers().size()));

        for (String carrier : run.carriers()) {
            MountLane lane = newLane(dense);
            lane.setWindow(t0, total);
            long busy = 0;
            for (FrameRun.Span span : run.mountedSpansOn(carrier)) {
                busy += span.durationNanos();
                lane.addBlock(span.startNanos(), span.endNanos(),
                        "req-" + span.request() + " mounted here\n"
                                + FrameRun.millis(span.durationNanos()) + " ms",
                        "lane-carrier", eraClass);
            }

            // Without these the row would be a near-empty lane during the very stretch the
            // carrier was least available — the inversion in the class note holds only while
            // a waiting request has actually let go of its carrier.
            long held = 0;
            for (FrameRun.Span span : run.pinnedSpansOn(carrier)) {
                held += span.durationNanos();
                lane.addBlock(span.startNanos(), span.endNanos(),
                        "held by req-" + span.request() + ", which is waiting\n"
                                + FrameRun.millis(span.durationNanos()) + " ms pinned",
                        "lane-pinned", eraClass);
            }

            double percent = total == 0 ? 0 : 100.0 * (busy + held) / total;
            // The window total is already in the heading above, so it is dropped here rather
            // than pushing the percentage out of the column.
            String figure = held > 0
                    ? String.format(Locale.US, "ran %s ms · HELD %s ms  (%.1f%%)",
                            FrameRun.millis(busy), FrameRun.millis(held), percent)
                    : String.format(Locale.US, "busy %s ms of %s ms  (%.2f%%)",
                            FrameRun.millis(busy), FrameRun.millis(total), percent);
            carrierRows.getChildren().add(row(FrameRun.shortCarrier(carrier), carrier, lane,
                    figure, held > 0 ? "pinned" : null));
        }

        caption.setText((run.pinned()
                ? "Red is a carrier that a waiting request could not let go of. Every request "
                        + "here holds a monitor of its own, so nothing is contending — the "
                        + "carrier simply cannot leave.\n"
                : "")
                + "Blocks narrower than a few pixels are drawn at a minimum width so they can "
                + "be seen at all — under virtual threads a mounted stretch really is "
                + "microseconds against a wait of hundreds of milliseconds. The true figures "
                + "are on the right of every row.");
    }

    /** Uncover the board only as far as one instant. Used by the step controls. */
    public void revealUpTo(long nanos) {
        lanes.forEach(lane -> lane.setCutoff(nanos));
    }

    public void revealAll() {
        lanes.forEach(MountLane::revealAll);
    }

    private MountLane newLane(boolean dense) {
        MountLane lane = new MountLane();
        if (dense) {
            lane.getStyleClass().add("dense");
        }
        lanes.add(lane);
        return lane;
    }

    /**
     * The do-nothing request, drawn where it finally got to run.
     *
     * <p>Deliberately not one of {@code run}'s requests: it is not in the event stream, so it
     * cannot move {@code t0}, the window, or a single figure on any other row. It is one mark
     * on a lane, and the sentence on the right of it is the whole of slide 40.
     */
    private void addProbeRow(FrameRun run, boolean dense, long t0, long total) {
        FrameRun.Probe probe = run.probe();
        if (probe == null) {
            return;
        }
        MountLane lane = newLane(dense);
        lane.setWindow(t0, total);
        boolean starved = probe.starved();
        lane.addBlock(probe.startedNanos(), probe.startedNanos(),
                "the do-nothing request finally ran here\n"
                        + FrameRun.millis(probe.queuedNanos()) + " ms after it was submitted",
                starved ? "lane-pinned" : "lane-mounted", run.era().styleClass());

        String figure = starved
                ? String.format(Locale.US, "queued %s ms for 0 ms of work",
                        FrameRun.millis(probe.queuedNanos()))
                : String.format(Locale.US, "ran immediately · %s ms after submit",
                        FrameRun.millis(probe.queuedNanos()));
        requestRows.getChildren().add(0, row("health", HEALTH_TOOLTIP, lane, figure,
                starved ? "pinned" : null));
    }

    private static HBox row(String name, String tooltip, MountLane lane, String figure,
                            String labelState) {
        Label label = new Label(name);
        label.getStyleClass().add("lane-label");
        if (labelState != null) {
            label.getStyleClass().add(labelState);
        }
        if (tooltip != null) {
            label.setTooltip(new Tooltip(tooltip));
        }

        Label right = new Label(figure);
        right.getStyleClass().add("lane-figure");

        HBox.setHgrow(lane, Priority.ALWAYS);
        HBox box = new HBox(8, label, lane, right);
        box.getStyleClass().add("lane-row");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** The thread the request ran on, for the row's tooltip. */
    private static String threadLabelOf(FrameRun run, int request) {
        return run.eventsFor(request).stream()
                .map(event -> event.threadLabel())
                .filter(label -> label != null && !label.isBlank())
                .findFirst()
                .orElse(null);
    }

    public VBox getNode() {
        return node;
    }
}
