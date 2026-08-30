package loomdemo.ui;

import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import loomdemo.load.FrameRun;

import java.util.ArrayList;
import java.util.List;

/**
 * One horizontal row of a mount timeline: coloured blocks laid out against wall-clock time.
 *
 * <p>A {@code Pane} with its own {@code layoutChildren} rather than a {@code Canvas}, because
 * the children are then ordinary {@link Region}s carrying style classes — so the lanes are
 * themed by {@code app.css}, follow dark mode and grow with presentation mode's larger root
 * font, none of which a canvas would do without a second copy of the palette in Java.
 *
 * <p><strong>Slivers are widened, and the tab says so.</strong> Under virtual threads a
 * mounted stretch is microseconds against a wait of hundreds of milliseconds, so at any sane
 * width it is a fraction of a pixel and would simply not be drawn. Every block is therefore
 * given a floor of {@link #MIN_BLOCK_PX}. That makes running time look far larger than it is,
 * which is the opposite of flattering to the argument being made — the true figure sits on
 * the right of each row, and the board carries a caption saying the blocks are widened.
 */
public final class MountLane extends Pane {

    /** Narrower than this and a block is invisible; see the class note. */
    private static final double MIN_BLOCK_PX = 3;

    private record Block(long startNanos, long endNanos, Region node) {
    }

    private final List<Block> blocks = new ArrayList<>();
    private long t0;
    private long totalNanos = 1;
    private long cutoffNanos = Long.MAX_VALUE;

    public MountLane() {
        getStyleClass().add("mount-lane");
    }

    /** The window this lane is drawn against — shared by every lane, so rows line up. */
    public void setWindow(long t0, long totalNanos) {
        this.t0 = t0;
        this.totalNanos = Math.max(1, totalNanos);
        requestLayout();
    }

    public void clearBlocks() {
        blocks.clear();
        getChildren().clear();
        cutoffNanos = Long.MAX_VALUE;
        requestLayout();
    }

    /**
     * @param styleClasses applied on top of {@code lane-block}; the era class goes here too,
     *                     so a lane wears its own colour without this class knowing about eras
     */
    public void addBlock(long startNanos, long endNanos, String tooltip,
                         String... styleClasses) {
        Region node = new Region();
        node.getStyleClass().add("lane-block");
        node.getStyleClass().addAll(styleClasses);
        if (tooltip != null) {
            Tooltip.install(node, new Tooltip(tooltip));
        }
        blocks.add(new Block(startNanos, Math.max(endNanos, startNanos), node));
        getChildren().add(node);
        requestLayout();
    }

    /**
     * Reveal the lane only as far as {@code nanos}, clipping whatever block straddles it.
     *
     * <p>This is what makes stepping look like the run happening rather than a finished
     * picture being uncovered: the block the focus request is sitting in grows as the steps
     * advance, instead of appearing whole.
     */
    public void setCutoff(long nanos) {
        this.cutoffNanos = nanos;
        requestLayout();
    }

    public void revealAll() {
        setCutoff(Long.MAX_VALUE);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        for (Block block : blocks) {
            boolean visible = block.startNanos() <= cutoffNanos;
            block.node().setVisible(visible);
            if (!visible) {
                continue;
            }
            long end = Math.min(block.endNanos(), cutoffNanos);
            double x = fraction(block.startNanos()) * width;
            double w = Math.max(MIN_BLOCK_PX, (fraction(end) - fraction(block.startNanos())) * width);
            if (x + w > width) {
                x = Math.max(0, width - w);
            }
            block.node().resizeRelocate(x, 0, w, height);
        }
    }

    private double fraction(long nanos) {
        double f = (nanos - t0) / (double) totalNanos;
        return Math.max(0, Math.min(1, f));
    }

    /** Convenience for callers building tooltips off a span. */
    public static String describe(FrameRun.Span span) {
        String what;
        if (span.mounted()) {
            what = "running on " + span.carrier();
        } else if (span.pinned()) {
            what = "waiting — and " + FrameRun.shortCarrier(span.pinnedOn())
                    + " is waiting with it, pinned";
        } else {
            what = "waiting — no code running";
        }
        return what + "\n" + FrameRun.millis(span.durationNanos()) + " ms";
    }
}
