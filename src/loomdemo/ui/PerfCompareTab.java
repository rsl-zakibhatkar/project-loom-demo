package loomdemo.ui;

import javafx.scene.layout.VBox;
import loomdemo.DemoTab;

public final class PerfCompareTab implements DemoTab {
    private final VBox node = new VBox();
    public VBox getNode() { return node; }
    @Override public void runDemo() { }
    @Override public void stopDemo() { }
    @Override public void clearOutput() { }
    @Override public void shutdown() { }
}
