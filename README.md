# Loom Stage Demo

A macOS desktop app for a live conference talk about Java Project Loom and virtual
threads. Three demos, switchable via tabs:

1. **Threads 101** — what a thread is, in ten lines you can re-run on demand
2. **Thread Bomb** — what platform threads cost
3. **Performance Comparison** — what that cost does to a server

Demo 2 carries a past/present toggle; demo 3 carries all three eras:

- **Take me to the past** — platform threads: one per task on demo 2, a pool of 200 on
  demo 3 · orange `#FFAB40`
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

**This is one program, run twice.** Both modes ask for a million threads, each sleeping one
second, and count how many they actually get. The two sources differ by a single word on a
single line, and they run under identical JVM flags. Say that out loud before you press
anything — it is what stops the demo looking arranged.

1. **Thread Bomb** tab. Toggle is on **Take me to the past**.
2. Talk over the code on the left. Point at the marked line — `Thread.ofPlatform()` — and
   say it is the only thing that will change. The code is real, runnable and editable.
3. **Run** (`⌘R`). The console streams `alive: 100 … 2,000` climbing.
4. It dies in well under a second. The punchline lands in huge orange type:
   **`Died at thread #2,021 of 1,000,000`**
5. Switch to **Take me to the present**. The editor swaps — invite the room to spot the
   difference before you say it. It is `ofPlatform` → `ofVirtual`, and nothing else.
   (If you edited the code, you get an inline prompt before it is discarded.)
6. **Run**. Same climb, four orders of magnitude further, then a pause on
   `All 1,000,000 threads are alive. Waiting for them...` while the counter drains:
   **`Completed 1,000,000 tasks in ~5.5s`**
7. The scoreboard at the top now reads
   `PAST died at #2,021 | PRESENT 1,000,000 ✓ in 5.56s`. It stays there for the rest of the
   talk, including when you switch tabs.

**Both runs print the same command line** — `$ java -Xmx2g -Xss1m Demo.java` — at the top of
the console. Scroll up and show it if anyone suspects the flags did the work. `-Xss1m` is
the platform thread's 1 MB stack; virtual threads simply ignore it, which is the point.

**Numbers to expect.** The death point is whatever your machine's thread limit allows —
2,019–2,021 across thirteen runs on an M2 (`kern.num_taskthreads` is 2048), higher on
machines with a larger limit. It varies per machine and that is fine; the gap to 1,000,000
is the point. Check yours during the warm-up run so you can quote it confidently.

The past side reaches the limit in about 90 ms, well inside the one-second sleep, so no
thread has retired yet and death is certain. On a machine with a *much* higher thread limit
— tens of thousands — creation could get slow enough that early threads start finishing and
the program survives longer than you want. It has not happened on any Mac tested, but if
your warm-up run does not die, raise the sleep in the editor and it will.

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
   - Say what the services are before anything else: `findUser`, `findOrder`, `chargeCard`,
     three dummies that do nothing but wait. Nobody needs to care what they do. What matters
     is that each one needs the answer from the one before it — every controller in the room
     looks like this.
   - Past and present share one handler, byte for byte. Three calls, three lines, in order.
     Read them out; they read as English.
   - The workaround does the same three calls with **5 callbacks**, two of them nested. Point
     at the nesting and say why it is there: `chargeCard` needs *both* `user` and `order`,
     and a `CompletableFuture` only carries the last value forward — so `user` is only
     reachable from inside the outer lambda.
   - **`That is the whole talk.`** Same throughput. One of them is code you can read.
7. **Then prove the last sentence.** Press **Break it**. One request goes to an endpoint
   that always fails, through both handler shapes, and the two real stack traces come back
   side by side. No load test, no waiting.
   - Both traces name the payment gateway — the same service that was on the handler slide a
     moment ago. Say so first; you are not claiming async loses your code.
   - Then count what is around it. The blocking trace names **three** of your methods:
     `callPaymentGateway`, `chargeCard` above it, and `boomHandler` above that. The async
     trace names **one**. `chargeCard` is missing because the async service layer *composes*
     on the gateway rather than calling it and waiting — it had already returned. `boomHandler`
     is missing for the same reason, one level up. Every boundary costs a frame.
   - The blocking trace also carries the request: `Filter.doFilter`, `Exchange.run`,
     six frames of it, in the era's own colour. The async trace has **none** — it runs
     `AsyncSupply.run` → `runWorker` → `Thread.run` and stops. Nothing is abbreviated; that
     really is the bottom of that stack.
   - The counts underneath read **6** and **0**.
   - **`Same failure. One trace tells you where.`**

**If someone says "just call getCause()"** — they are right, and the panel already shows
the `Caused by:` section with all three of your frames in it. Point at it. Then point at
what unwrapping cannot give back: the request is not on that stack at any depth. That is
what the counter counts, and it is why it says frames tying this to a *request* rather
than frames from your code.

**And if they follow up with "so what have I actually lost?"** — the async panel is the
*longer* of the two and still says less. Read it bottom-up. The blocking trace is one
continuous story: a request arrived, went through the filter chain, reached your handler,
which loaded an order, which called the gateway. The async trace starts at a pool worker.
Everything your stage called synchronously survives; everything that called *it* does not.
Here that costs six frames. In a real pipeline every `thenApply` is another cut, and the
earlier stages go too — see *What is actually lost* below for the measured version.

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
| The thread bomb does not die at all | Only possible on a machine whose thread limit is high enough that creation outruns the one-second sleep. Change `Duration.ofSeconds(1)` to `ofMinutes(1)` in the editor and run again — the past side dies for certain, and you simply stop before running the present side, which would now never finish. |
| Break it shows an error in a panel | It could not reach the server. Press **Show the chart** and then **Break it** again — it rebinds the server each time, so a second attempt is a fresh start. The error stays inside the panel and takes nothing else down. |
| Someone says async keeps the stack trace | Agree, and show them: the `Caused by:` section is right there, naming the payment gateway. Then read the counts out loud — three of your methods against one, and the request path 6 against 0. `getCause()` brings back none of it. |
| Someone asks what was lost, if all the frames are there | Every layer boundary. `chargeCard` called the gateway and waited on the blocking side, so it is on the trace; on the async side it composed and returned, so it is not. Same for the handler, and for all six request frames. Do **not** claim `findUser`/`findOrder` — they had returned and are on neither trace. |
| Someone says you would use a record, not nested lambdas | Agree — the comment under the async handler already says so. That is the trade: two nested lambdas, or a type your domain never asked for, threaded through every stage. The blocking version needs neither. |
| Someone says the two programs are different | Scroll the console to the `$ java …` line: it is identical in both runs. Then put the two sources side by side — one word, one line. That is the whole answer. |
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
(`java Demo.java`), under `-Xmx2g -Xss1m` — **the same flags in both modes**. Its stdout
and stderr are merged and streamed back into the console at about 30 Hz.

The child JVM is the runtime bundled inside the `.app`, so nothing needs to be installed
on the presenting machine. That runtime is jlinked **with `jdk.compiler` and `jdk.zipfs`**,
which the source launcher silently requires — `make verify` exists to catch that before it
becomes a problem on stage.

Two output lines are load-bearing: `Died at thread #N of M` and `Completed 1,000,000 tasks
in Xs`. The app watches for those and promotes them to the punchline banner and the
scoreboard. Edit them live and the demo still runs, you just lose the banner.

### Demo 2's two programs are the same program

`DemoSources` holds **one** template. The two modes are that template with `$FACTORY$`
replaced by `ofPlatform` or `ofVirtual`, so they cannot drift apart — not the comments, not
the prints, not the flags. It would take an edit to the template to make anything else
differ, which is exactly the property the demo is claiming.

That constrains the code in two ways worth knowing before you edit it:

- **The progress print is one shared rule** — chatty below ten thousand, every hundred
  thousand above it. Past gets its twenty lines of climb; present gets a fast blur and then
  ten `completed:` lines while it drains. Neither side gets a print tuned for itself.
- **`executor.close()` is called explicitly, not by try-with-resources.** With
  try-with-resources, the `OutOfMemoryError` would unwind *through* `close()`, which waits
  out two thousand one-second sleeps — the death message would arrive a second late, after
  a visible stall. Calling `close()` inside the `try` lets the `catch` reach `System.exit`
  while the corpses are still warm.

The past side's OOM arrives raw and unwrapped out of `executor.submit()` on the main
thread, so the console shows the real
`java.lang.OutOfMemoryError: unable to create native thread` text rather than something the
demo made up. The two `[warning][os,thread]` lines above it are the JVM's own, on stderr.

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

### Break it counts request frames, not "your" frames

The obvious version of this exhibit counts frames belonging to the app and shows *something*
against **0**. That version is wrong, and it would not survive the first sharp question.

`.exceptionally(failure -> …)` receives a `CompletionException`. Its *own* stack trace is
pure `CompletableFuture` machinery — but it wraps the original `IllegalStateException`,
which was constructed inside `callPaymentGateway`, so a `Caused by:` section carries all
the app's frames from inside that stage — here, the gateway itself. A zero is only true if
the panel hides that section, and someone who says *"just call `getCause()`"* would be right.

So the panel shows the whole trace, cause included, and counts something that is genuinely
zero on one side: frames naming the HTTP server's request path. Measured on this JDK:

| | total frames | app | request path |
| --- | --- | --- | --- |
| past, blocking | 12 | 3 — gateway, `chargeCard`, handler | **6** |
| workaround, async | 13 | 1 (under `Caused by:`) — the gateway alone | **0** |
| present, blocking | 11 | 3 — gateway, `chargeCard`, handler | **6** |

Both traces name the payment gateway. Only one says a request was involved — and no amount
of unwrapping puts it back, because it was never on that stack. That is exactly the claim
the async handler's own comment makes, and now the tab demonstrates it.

Note the async trace is the **longer** of the two and still says less. That is the shape of
the whole problem, and it is worth saying out loud: what async costs you is not frames, it
is the caller chain. A stack trace answers two questions — *what broke* and *what was being
done*. Async still answers the first perfectly. It stops answering the second.

### What is actually lost, if the frames all survive

The strongest objection to this exhibit is that the `Caused by:` section looks just like the
blocking trace, so what is the problem? The honest answer is that **everything a stage calls
synchronously stays on its stack, and everything below the stage boundary does not.** The
async trace is not missing your code. It is missing the context in which your code ran.

The failure is deliberately in the **third** service, not the first, and the two traces show
exactly what that costs. Read them bottom-up:

| | reads as |
| --- | --- |
| blocking | `Thread.run ← Exchange.run ← doFilter ← boomHandler ← chargeCard ← callPaymentGateway` |
| async | `Thread.run ← runWorker ← AsyncSupply.run ← callPaymentGateway` |

The blocking trace is one continuous story: a request arrived, went through the filter chain,
reached your handler, which charged a card, which called the gateway. The async trace starts
at a pool worker and gets one frame in. Three of your methods against one.

**Be careful about which frames are missing, because it is easy to overclaim here.**
`findUser` and `findOrder` are on **neither** trace. They returned before `chargeCard` was
ever called, so they popped off the blocking stack too — a stack trace is the live call
chain, not a history of the request. If someone points that out, they are right, and the
exhibit does not need them.

What is genuinely missing from the async side is **every layer boundary**: `chargeCard`,
`boomHandler`, and the six request frames under it. Those were all live on the blocking
stack at the moment of failure.

### Why adding more services would not help

Worth knowing before anyone suggests a bigger service chain would make the point harder:

- **Sequential calls** — `findUser`, `findOrder` — have already returned. They are on neither
  trace, so adding ten more changes nothing on either side.
- **Nested synchronous calls** survive on *both* sides. Everything a stage calls
  synchronously stays on its stack, cause section included. Adding depth adds it to blocking
  and async equally.
- **Layer boundaries** are the only thing that separates them. Each `thenApply` /
  `thenCompose` / `supplyAsync` between you and the failure is one frame blocking keeps and
  async does not.

Which is why `AsyncOrderService.chargeCard` returns
`callPaymentGateway(...).thenApply(...)` rather than calling the gateway inline inside one
supplier. Inline, the frame survives and the exhibit is weaker by one — but that is not how a
service layer whose methods return futures gets written, because it cannot call the layer
below it and wait. Either shape is defensible and either one costs you something; this is the
one people actually write.

Every `thenApply` / `thenCompose` is another cut, so a deeper pipeline loses more. Measured,
the same four steps written both ways, failing in the last:

| | frames from your code | what they tell you |
| --- | --- | --- |
| nested calls, blocking | 5 | `callGateway ← chargeCard ← reserveStock ← checkout ← main` |
| composed stages, async | 3 | `callGateway ← chargeCard ← reserveStock ← ` a pool worker |

`checkout` and `main` are gone the same way. The trace tells you the gateway call failed
inside `reserveStock`; it cannot tell you that `reserveStock` was reached from a checkout,
because it wasn't — it was reached from `ThreadPoolExecutor.runWorker`.

Two shapes make it worse still, and are worth mentioning if someone pushes: a future
completed from an I/O callback via `completeExceptionally` carries the stack of *whatever
thread constructed the exception*, which may contain none of your code at all; and
`orTimeout` fails you from a timer thread, with a `TimeoutException` whose trace is a
scheduler and nothing else.

Virtual threads give the second question back for free, because the whole request really is
one stack.

Two consequences worth knowing before editing any of it:

- **`/order/boom` is deliberately not an `Endpoint` constant.** The tab fills its endpoint
  dropdown straight from `Endpoint.values()`, so a fourth constant would offer the
  presenter a handler that throws on every request as a target for a 5,000-request load
  test. It is registered as its own context and only Break it ever asks for it.
- **The mid-trace truncator is set to collapse runs of 8+ framework frames, and never
  fires on a real trace here.** The async trace's longest framework run is *six* —
  `encodeThrowable`, `completeThrowable`, `AsyncSupply.run`, `runWorker`, `Worker.run`,
  `Thread.run` — and those six frames are the pool worker the whole exhibit is about.
  Collapsing them would hide the point. The threshold exists for a pathologically deep
  trace on some other runtime; the harness asserts it stays dormant.
- **`respondTrace` expands the trace itself rather than calling `printStackTrace`.** That
  method abbreviates a cause to the frames it does not share with its wrapper, ending the
  section with `... 3 more` — which stopped the async cause at `AsyncSupply.run` and invited
  the one question the exhibit exists to answer: *is the request hiding under the "... 3
  more"?* It is not, and showing the frames proves it where asserting it does not. Every
  frame comes from `getStackTrace()`; only the abbreviation is dropped. Nothing on screen is
  ever elided now, by us or by the JDK, and both harnesses check for a stray `...`.

Break it rebinds the server to each era in turn to get a real trace from each, preferring
whichever era is already running so a warm server costs nothing. It never runs a load test,
and it does not need one to have been run.

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
noisier, for 5 callbacks against 0.

That tie is the entire reason the era is in the app. A comparison that only showed past
against present would lose to the first person who says "I'd use CompletableFuture" —
because they would be right. Showing that they are right, and then showing the two
handlers, is a much stronger position than never raising it.

**The handler sources on screen are extracts**, held as constants next to the methods they
mirror in `OrderServer.java`. Edit one, edit the other — the `Eras` harness asserts that all
three service calls appear in both. The counts under the panels ignore blanks and comments,
so the async version is not penalised for explaining itself.

### Why the panel counts callbacks and not lines

It used to say *"25 lines of handler against 18"*. That number was quietly measuring the
wrong thing.

The old blocking handler was 18 lines of which **one** was business logic — the other 17
were `com.sun.net.httpserver` plumbing (`sendResponseHeaders`, `getResponseBody`,
`exchange.close()`), an API nobody in a Spring room has ever written. So the slide asked the
audience to decode an unfamiliar 2006 HTTP API to reach a point that was one line deep, and
a good chunk of the 7-line gap was just *"async also has to write the response"* — true, but
small, and arguable, and doing most of the work in the headline.

Both handlers now share `respond(...)`, because both need a response written and neither
should be billed for it. With that fixed, the async handler comes out at **9 lines against
blocking's 12** — *shorter*. Propping the old figure up would have meant leaving boilerplate
on the async side purely to keep the number, which is the same rigged-evidence problem
removed from Demo 2 and from the Break it counter.

What survives the fair comparison is control flow, so that is what gets counted:

| | lines | callbacks | reads in order |
| --- | --- | --- | --- |
| past / present, blocking | 12 | **0** | yes |
| workaround, async | 9 | **5** | no — two nested |

`handlerCallbackCount` counts `->` on comment-free lines and subtracts the handler's own, so
the number on the panel is derived from the exact string the audience is looking at and
cannot drift from it.

### The three services split the latency, they do not add to it

`Timings.split` shares each endpoint's advertised budget 40/35/25, remainder to the last
slice so it sums exactly: 100 ms becomes 40/35/25, 300 becomes 120/105/75, 20 becomes 8/7/5.
Per-request wall time is unchanged, which is why every throughput number in this file still
holds after the rewrite. Uneven on purpose — three equal waits look like a loop, and the
point is three different calls.

The services sleep and return a record. Nothing else. `OrderService.pause` restores the
interrupt flag before rethrowing unchecked, so the handler needs one `catch` rather than two
and the executor can still interrupt its workers on `stop()`.

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
