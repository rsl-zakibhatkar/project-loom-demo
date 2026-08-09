package loomdemo;

/**
 * What the global keyboard shortcuts act on. The shell dispatches Cmd+R / Cmd+. / Cmd+K
 * to whichever tab is currently showing.
 */
public interface DemoTab {
    void runDemo();

    void stopDemo();

    void clearOutput();

    /** Called when the window closes so tabs can kill child processes and servers. */
    void shutdown();
}
