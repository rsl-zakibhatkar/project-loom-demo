# Loom Stage Demo

A macOS desktop app for a live conference talk about Java Project Loom and virtual
threads. Three demos, switchable via tabs:

1. **Threads 101** — what a thread is, in ten lines you can re-run on demand
2. **Thread Bomb** — what platform threads cost
3. **Performance Comparison** — what that cost does to a server

Demo 2 carries a past/present toggle; demo 3 carries all three eras:

- **Take me to the past** — platform threads, bounded pools · orange `#FFAB40`
- **Take me to the workaround** — async callbacks, one thread per core · violet `#7E57C2`
- **Take me to the present** — virtual threads, since Java 21 · teal `#0097A7`

Deliberately *present*, not *future*: virtual threads went final in Java 21 in September
2023. Calling them the future on stage makes them sound like something to wait for rather
than something the room could have shipped two years ago.

The workaround era exists to answer the one objection that beats a two-sided demo: *"I'd
use CompletableFuture and get the same numbers without Loom."* That is true, and the tab
shows it is true — then puts the two handlers side by side.

Built with JavaFX on Java 21, shipped as a `.dmg` with a bundled runtime. The presenting
machine needs no JDK and no network.

---

## Build

Everything is one command. The first build downloads its own toolchain (Temurin JDK 21,
JavaFX 22, and the RichTextFX editor jars, about 250 MB) into `tools/` and `libs/`.
Nothing is installed system-wide and no admin rights are needed.

```bash
make
```

That produces `dist/Loom Demo-1.0.0.dmg`. **The first build needs an internet
connection; every build after that, and the app itself, are fully offline.**

### Other targets

| Command | What it does |
| --- | --- |
| `make run` | Compile and launch straight from `out/` — the fast loop while editing |
| `make dmg` | Full build: compile → jlink runtime → verify → jpackage (same as `make`) |
| `make verify` | Prove the bundled runtime can run single-file `.java` sources |
| `make toolchain` | Just fetch the JDK/JavaFX/jars |
| `make clean` | Remove `out/`, `build/`, `dist/` — keeps the downloaded toolchain |
| `make distclean` | Also delete `tools/` and `libs/` (forces a re-download) |

### Rebuilding after a change

```bash
make run
```

for a quick look, then

```bash
make dmg
```

when you want a new installer. `make` only recompiles what changed; a full `make dmg`
from warm caches takes well under a minute. If a build ever behaves strangely, `make
clean && make` is safe and does not re-download the toolchain.

### Signing (optional)

Unsigned is fine on your own machine. To sign with a Developer ID:

```bash
make dmg SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)"
```

Without signing, a Mac that did not build the app will refuse the first launch.
Right-click the app → **Open** → **Open**, once, and macOS remembers. Or:

```bash
xattr -dr com.apple.quarantine "/Applications/Loom Demo.app"
```

---

## Keyboard shortcuts

| Key | Action |
| --- | --- |
| `⌘R` | Run the current tab's demo |
| `⌘.` | Stop |
| `⌘K` | Clear console (tabs 1 and 2) / reset stats (tab 3) |
| `⌘P` | Toggle presentation mode (+30% on every font) |
| `⌘D` | Toggle light/dark |

The app follows your macOS light/dark setting until you touch the theme button, after
which your choice sticks for the session. **Use the light theme on a projector** — dark
themes wash out badly on cheap hardware.

---

## Stage runbook

### Before you go on

1. Launch the app once and leave it open. Startup is about a second, but do it anyway.
2. Turn on **Presentation** if the room is deep.
3. Switch to the **light theme** unless you have checked the projector.
4. Run each demo once as a warm-up, then `⌘K` on all three tabs. First runs are always the
   slowest, and you would rather spend that on your own laptop than on stage.
5. Plug in the power cable. Demos 2 and 3 are CPU-heavy for a few seconds and laptops
   throttle hard on battery.

### Demo 1 — Threads 101

The app opens here. Three snippets, left to right, in the order you want them.

1. **Two Cooks** is preselected. Two threads, five lines each. Talk over the code, then
   **Run** (`⌘R`). `cook-1` and `cook-2` come back in two different colours.
2. **Run it again. And again.** This is the whole demo. Each run is separated by a
   `──── Run #N ────` rule and the console *keeps* the earlier runs, so three runs stack up
   on screen and the audience can see for themselves that the order changed and the code
   did not. Re-running is instant — about 25 ms to the first line — so you can press it
   three times in a row without a pause to talk over.
3. Ask what would happen if you called `run()` instead of `start()`, take a guess from the
   room, then switch to **run() vs start()**. Same code, two characters different. Every
   line now says `main`, in one colour, in order. Nobody argues with that.
4. Finish on **Who's in my JVM?** — a program that starts no threads at all and still
   finds six. "You have never written a single-threaded program."

The caption above the editor changes with each snippet and says what to look for. The code
is editable: change `i < 5` to `i < 20` and re-run if you want a longer interleave.

**If you edit the code and then switch snippets**, an inline bar asks before replacing it.
**Reset** restores the original snippet.

### Demo 2 — Thread Bomb

1. **Thread Bomb** tab. Toggle is on **Take me to the past**.
2. Talk over the code on the left. It is real, runnable, and editable — change the sleep
   duration or the print interval live if you want.
3. **Run** (`⌘R`). The console streams the thread count climbing.
4. It dies in well under a second. The punchline lands in huge orange type:
   **`Died at thread #2,021`**
5. Switch to **Take me to the present**. The editor swaps to the virtual-thread version.
   (If you edited the code, you get an inline prompt before it is discarded.)
6. **Run**. Watch it climb through a million, then:
   **`Completed 1,000,000 tasks in ~4.9s`**
7. The scoreboard at the top now reads `PAST died at #2,021 | PRESENT 1,000,000 ✓`. It
   stays there for the rest of the talk, including when you switch tabs.

**Numbers to expect.** The death point is whatever your machine's thread limit allows —
around 2,000 on an M2 (`kern.num_taskthreads` is 2048), higher on machines with a larger
limit. It varies per machine and that is fine; the gap to 1,000,000 is the point. Check
yours during the warm-up run so you can quote it confidently.

### Demo 3 — Performance Comparison

1. **Performance Comparison** tab. Defaults are `GET /order/{id}` (100 ms), 5,000
   requests, 2,000 concurrent — these give the sharpest contrast. **Requests** and
   **Concurrency** are buttons, not dropdowns: one click, no popup to miss.
2. Set the toggle to **Take me to the past**, press **Run**.
   - It briefly says *warming up connections…* — that is the load generator opening its
     connection pool before the clock starts. Then the orange line climbs on the chart.
   - Left panel fills: roughly **1,850 req/s, p99 ~1,050 ms**.
3. Switch to **Take me to the present**, press **Run** again.
   - Right panel fills: roughly **11,700–12,800 req/s, p99 ~150–185 ms**. The teal line
     hugs the bottom of the chart.
4. The line underneath is the one they will remember:
   **`Present: 6.8× throughput, p99 latency 1,072 ms → 152 ms`**
5. **Now take the objection before someone else does.** Ask the room how they would fix
   the past number without Loom. Someone says CompletableFuture, or reactive, or WebFlux.
   Switch to **Take me to the workaround** and press **Run**.
   - A third panel slides in between the other two, in violet: roughly **10,600–14,900
     req/s, p99 ~140–220 ms** on **one thread per core**. It matches virtual threads, and
     a second line appears saying so.
   - Say the quiet part: *they are right*. Async really does solve this.
6. Press **Show the handlers**. The chart is replaced by the handler each era actually
   runs, and the era buttons now flip the code instead of the run.
   - Past and present share one handler, byte for byte: **18 lines**, one `Thread.sleep`,
     one `try/finally`, and a stack trace that names your own method when it breaks.
   - The workaround needs **25 lines**, a `CompletableFuture` chain, a second method to
     write the response, and a `catch` block with nowhere to throw to.
   - **`That is the whole talk.`** Same throughput. One of them is code you can debug.

**Say this out loud**: the handler code is identical in both runs. The only thing that
changed is the executor the server hands requests to. And the load generator uses virtual
threads in *both* runs, so the client is never the bottleneck — otherwise the past
numbers would just be measuring the test harness.

**A good extra beat, if you have time.** Set concurrency to **100** and endpoint to
`/order/fast/{id}`, and run both modes. They come out nearly identical (~3,300 req/s
each). Below the size of the thread pool, threads are threads. Virtual threads are not
magic speed — they are what stops you falling off a cliff when concurrency exceeds your
pool. That honesty buys you a lot of credibility.

If you run the two sides with different settings, a red line appears telling you the
comparison is invalid. Re-run one side to match.

### If something goes wrong mid-demo

| Symptom | What to do |
| --- | --- |
| A run seems stuck | `⌘.` (Stop). Tabs 1 and 2 kill the child JVM; tab 3 abandons the load test and **keeps the previous result on screen**, so you never lose a filled panel to a bad run. |
| Console is cluttered | `⌘K`. On tab 1 this also resets the run counter back to #1. On tab 3 it resets both panels and the chart, so only use it if you mean it. |
| Two Cooks produced the same order twice | Say so — it is genuinely random, not guaranteed to differ. Run it once more. Two identical runs followed by a different one makes the point better than a lecture would. |
| The workaround beat virtual threads | Expected, and fine. They are the same number and the run-to-run spread is wider than the gap. Say "same speed" and move to the handlers — that is where the argument actually is. |
| Thread bomb dies at a surprising number | Say the number out loud and move on. It is machine-specific and the contrast is unaffected. |
| Errors appear on a stats panel | Hover the errors figure for the actual failure kinds. Most likely something else on the machine is holding ports or CPU. Press Stop, then Run again. |
| The code got edited into something broken | **Reset** button above the editor restores the original source for that mode or snippet. If you run it broken first, the real `javac` error appears in the console — which is a fine thing to show on purpose. |
| An unexpected error | It goes to the output console, not a crash dialog. The window will not go down with the demo — that is the whole reason the thread bomb runs in a child JVM. |
| Total disaster | Quit and relaunch. Startup is ~1s and no state is needed to re-run either demo. |

---

## How it works

### Tabs 1 and 2 run your code in a different JVM

The past-mode program deliberately exhausts the OS thread limit. Running it in the app's
own process would take the window down with it. Instead the editor's contents are written
to a temp file and executed by a child process using single-file source execution
(`java Demo.java`), with `-Xmx512m -Xss1m` for past and `-Xmx2g` for present. Its stdout
and stderr are merged and streamed back into the console at about 30 Hz.

The child JVM is the runtime bundled inside the `.app`, so nothing needs to be installed
on the presenting machine. That runtime is jlinked **with `jdk.compiler` and `jdk.zipfs`**,
which the source launcher silently requires — `make verify` exists to catch that before it
becomes a problem on stage.

Two output lines are load-bearing: `Died at thread #N` and `Completed 1,000,000 tasks in
Xs`. The app watches for those and promotes them to the punchline banner and the
scoreboard. Edit them live and the demo still runs, you just lose the banner.

### Threads 101 compiles ahead of time so re-runs are instant

The source launcher recompiles on every launch, which costs about 250 ms before the first
line of output. That is invisible in Thread Bomb, where the program then runs for seconds.
It is not invisible in a demo whose entire point is pressing Run three times in a row. So
Threads 101 compiles the snippet in the background whenever the editor settles, and Run
then only has to start a JVM: about **25 ms** to the first line instead of 250.

If the snippet is not compiled yet, or the presenter has just broken it, the app silently
falls back to the source launcher — same behaviour the other tab has always had, and it is
that fallback that puts the real `javac` error on screen.

Compilation happens in-process through the `javac` `ToolProvider`, so the jlinked runtime
needs `jdk.compiler` — which it already did, for the source launcher. `make verify` checks
both, because neither failure would ever show up in `make run`.

Console lines are tinted by which thread printed them. A name keeps its colour until the
console is cleared, so `cook-1` looks the same in run #3 as it did in run #1 — otherwise
comparing one run against the one above it would mean nothing. The colours are deliberately
neither orange nor teal: those two mean "before Loom" and "after Loom" everywhere else in
the talk, and a `cook-1` that looked orange would quietly say something untrue.

### Why the settings on tab 3 are buttons, not dropdowns

**Requests** and **Concurrency** used to be `ComboBox`es. A JavaFX dropdown list lives in a
separate native window, and on macOS that window intermittently paints blank: you click,
get an empty rectangle, and the options only appear once you move the mouse across them.
The list is never actually wrong — driving the control from a harness shows the right
items, the right fonts and the right theme colours every time, and forcing a repaint draws
them correctly. It is the window that does not get painted, which puts it out of reach of
anything the application can do in CSS.

For three fixed values that is not worth working around, so there is no popup any more:
every option is a `ToggleButton` that is always on screen. It is also one click instead of
two and readable from the back of the room, which is what the rest of this app optimises
for. `SegmentedPicker` is the shared widget; the Threads 101 snippet switch is the same
shape.

**Endpoint is still a dropdown.** Its three labels are HTTP paths, and side by side they
push the control row to three lines at presentation font sizes. If it ever glitches on
stage, click it a second time — and the same treatment would work there if you are willing
to shorten the labels.

### Tab 3 measures a real server

`com.sun.net.httpserver` on loopback, ephemeral port. Every endpoint waits out its latency
and returns JSON; the wait stands in for a database call. Changing era restarts the server
with a different executor:

| Era | Executor | Handler |
| --- | --- | --- |
| past | `newFixedThreadPool(200)` | blocking |
| workaround | `newFixedThreadPool(availableProcessors())` | async |
| present | `newVirtualThreadPerTaskExecutor()` | blocking — **identical to past** |

Past and present share a handler byte for byte. Only the workaround needs different code,
and that is the argument the tab is making.

### The workaround era genuinely does not block

The async handler registers a continuation and returns, so its thread is free before the
simulated database call has even started. `CompletableFuture.delayedExecutor` is the stand-in
for a non-blocking driver — "this completes in N ms without holding a thread". One small
pool does request parsing *and* response writes, which is what a reactive event loop is.

This works because `com.sun.net.httpserver` does not close an exchange when the handler
returns: `ServerImpl.Exchange.run()` ends right after the handler chain, the request and
response reapers are disabled by default (`maxReqTime` / `maxRspTime` are `-1`), and an
in-flight connection is removed from both idle-sweeper sets. Another thread can complete
the exchange later. **Do not set `sun.net.httpserver.maxReqTime` or `maxRspTime`** — either
one would start killing async requests mid-flight.

Measured on an 8-core M2 at 5,000 requests / 2,000 concurrent: **peak 2,000 exchanges open
at once on 8 threads**, zero errors.

**The workaround ties with virtual threads, and that is the honest result.** Across four
runs: past 1,700–1,900 req/s, workaround 10,600–14,900, present 11,700–12,800. The
workaround won three of four — inside the run-to-run spread, which is wider on the async
side than the virtual-thread side. Async is not more throughput; it is the same throughput,
noisier, for 25 lines of handler against 18.

That tie is the entire reason the era is in the app. A comparison that only showed past
against present would lose to the first person who says "I'd use CompletableFuture" —
because they would be right. Showing that they are right, and then showing the two
handlers, is a much stronger position than never raising it.

**The handler sources on screen are extracts**, held as constants next to the methods they
mirror in `OrderServer.java`. Edit one, edit the other. The line counts under the panels
ignore blanks and comments, so the async version is not being penalised for explaining
itself.

Each run has an untimed warm-up that opens one pooled connection per unit of concurrency,
then the timed run. The warm-up is not cosmetic: without it, the measured phase opens
every connection at once, macOS's 128-entry accept queue (`kern.ipc.somaxconn`) resets the
overflow, and you get around a hundred errors *on the virtual-thread side only* — where every
request really is in flight at once. It reads as "virtual threads are flaky" when it is
really an artefact of the test rig. The server's idle keep-alive ceiling is raised past
the highest offered concurrency for the same reason. With both in place, every offered
configuration reports zero errors on both sides.

### Colours

`#FFAB40` and `#0097A7` are used at full strength for fills, borders, chart lines and
badges. Large *text* on a light background uses a darkened variant of the same hue —
`#FFAB40` on white is about 1.9:1 contrast and is genuinely unreadable from the back of a
room. Dark theme flips them back to bright.
