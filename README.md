# Loom Stage Demo

A macOS desktop app for a live conference talk about Java Project Loom and virtual
threads. Six demos, switchable via tabs:

1. **Threads 101** — what a thread is, and what two of them share, in code you can
   re-run on demand
2. **Thread Bomb** — what platform threads cost
3. **Thread-per-Request** — why we spent one thread per request anyway: it was the good
   design
4. **Frame by Frame** — one blocking call, measured: mounting, unmounting, and the
   carrier somebody else picks up
5. **Performance Comparison** — what that cost does to a server
6. **A Million Lockers** — the `ThreadLocal` memory leak on reused threads, and
   `ScopedValue`

Demos 2 and 4 carry a past/present toggle; demo 5 carries all three eras:

- **Take me to the past** — platform threads: one per task on demos 2 and 4, a pool of 200
  on demo 5 · orange `#FFAB40`
- **Take me to the workaround** — async callbacks, one thread per core · violet `#7E57C2`
- **Take me to the present** — virtual threads, since Java 21 · teal `#0097A7`

Demo 4 has no workaround era on purpose: the async handler has no thread to follow, which
is the argument demo 5's **Break it** view already makes.

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
| `⌘K` | Clear console (tabs 1–3 and 6) / reset the board (tab 4) / reset stats (tab 5) |
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
4. Run each demo once as a warm-up, then `⌘K` on all six tabs. First runs are always the
   slowest, and you would rather spend that on your own laptop than on stage.
5. Plug in the power cable. Demos 2 and 3 are CPU-heavy for a few seconds and laptops
   throttle hard on battery.

### Demo 1 — Threads 101

The app opens here. Six snippets, left to right, in the order you want them.

1. **Two Cooks** is preselected. Two threads, three orders each. Talk over the code, then
   **Run** (`⌘R`). `Cook#1` and `Cook#2` come back in two different colours, and every
   order is a pair: `receives`, then the oven, then `serves`. The gap between a cook's two
   lines is the thread being somewhere else — which is where the other cook's lines land.
2. **Run it again. And again.** This is the whole demo. Each run is separated by a
   `──── Run #N ────` rule and the console *keeps* the earlier runs, so three runs stack up
   on screen and the audience can see for themselves that the order changed and the code
   did not. Re-running is instant — about 25 ms to the first line — so you can press it
   three times in a row without a pause to talk over.

   Then, before you move on: ask the room to stop reading the names and read the *order
   numbers* instead. There are two Order #1s. There are two Order #2s. Nothing raced —
   both threads did exactly what the code says, every time. The code says the wrong thing:
   `orderNo` is a local, and a local cannot identify an order that belongs to the whole
   kitchen. Do not fix it yet. Just let the room see it.
3. Now fix it. Switch to **Two Cooks, Shared** — the same two cooks, the same loop, and
   one new object. Say "the only new thing is one order pad" before you run it. Then read
   the two columns: the ticket on the left now runs 1 to 6 with no number appearing twice,
   while `my N of 3` on the right resets to 1 when the other cook takes over. Same console
   line, two counters, and they behave differently because they live in different places —
   the tally is a local on that cook's own stack, the ticket comes off one object on the
   heap that both cooks reach. Read the last two lines out loud: `Each cook handled 3
   orders` against `The kitchen took 6 orders`. Worth pointing at the two `join()` calls —
   the summary cannot print early, because main is still waiting on both cooks. And worth
   naming what is holding the left column together: `AtomicInteger`. A plain `int++` there
   would hand two cooks the same ticket and put Two Cooks' bug back, only intermittently
   this time. The code says the fields are thread-safe on purpose and that the why is a
   later slide — promise it, and keep the promise at **One Oven**.
4. Ask what would happen if you called `run()` instead of `start()`, take a guess from the
   room, then switch to **run() vs start()**. Same code, two characters different. Every
   line now says `main`, in one colour, in order. Nobody argues with that.
5. Now switch to **One Oven**. The two cooks no longer just take turns talking — they
   share one object. Read the first two lines out loud: two threads, two colours, the
   *same* `Oven@…` on both. That is the heap, and it is the only copy there is. Then let
   the run finish. `wanted risotto, got lasagna` is not error handling — nothing threw.
   Both cooks did exactly what the code said. Worth landing where it went wrong: two
   `puts X in` lines run back to back, and `inside` is a single reference, so the second
   one does not stack a dish on top of the first — it destroys it. The cook whose dish
   went in the bin is asleep for another 200 ms, still believing it is baking.
   Most runs show a second ending somewhere in the six orders: `wanted lasagna, got null`.
   That is the same one slot from the other side. Coming back for a dish is two steps, a
   read and then a clear, and every cook clears unconditionally — so a cook who gets back
   second finds an oven the other one has already emptied, and walks off with nothing.
   Nothing threw there either. Both endings are the same bug: one reference, and no rule
   about who touches it when.
6. **One Oven, Locked** is the same program with one word added: `bake` is
   `synchronized`. Say that before you run it, and offer to diff the two sources — every
   other difference between them is a comment. Now every `puts X in` is followed by that
   same cook's own `wanted X, got X`, and no cook is ever inside the oven while another
   one is baking. Worth naming: the lock
   *is* the oven. The object they share is the object they take turns on. Who gets it
   first still varies run to run; what never happens again is two cooks inside it at once.
7. Finish on **Who's in my JVM?** — a program that starts no threads at all and still
   finds six. "You have never written a single-threaded program."

The caption above the editor changes with each snippet and says what to look for. The code
is editable: change `orderNo <= 3` to `orderNo <= 10` and re-run if you want a longer
interleave. In **Two Cooks, Shared** the loop counts `mine` up to the `ORDERS` constant,
and both summary lines are computed from it and from the pad — edit it live and the two
totals move together rather than going stale.

**If you edit the code and then switch snippets**, an inline bar asks before replacing it.
**Reset** restores the original snippet.

### Demo 2 — Thread Bomb

**This is one program, run twice.** Both modes ask for a million threads, each sleeping one
hour, and count how many they actually get. The two sources differ by a single word on a
single line, and they run under identical JVM flags. Say that out loud before you press
anything — it is what stops the demo looking arranged.

1. **Thread Bomb** tab. Toggle is on **Take me to the past**.
2. Talk over the code on the left. Point at the marked line — `Thread.ofPlatform()` — and
   say it is the only thing that will change. The code is real, runnable and editable.
3. **Run** (`⌘R`). The console prints `alive: 1,000`, then `alive: 2,000` — two lines is the
   whole climb the past side gets before the ceiling.
4. It dies in well under a second. The punchline lands in huge orange type:
   **`Died at thread #2,021 of 1,000,000`**
5. Switch to **Take me to the present**. The editor swaps — invite the room to spot the
   difference before you say it. It is `ofPlatform` → `ofVirtual`, and nothing else.
   (If you edited the code, you get an inline prompt before it is discarded.)
6. **Run**. The same counter, four orders of magnitude further — a thousand lines of it,
   blurring past in well under a second:
   **`All 1,000,000 threads alive`**
7. The scoreboard at the top now reads
   `PAST died at #2,021 | PRESENT 1,000,000 alive`. It stays there for the rest of the
   talk, including when you switch tabs.

**Both runs print the same command line** — `$ java -Xmx2g -Xss1m Demo.java` — at the top of
the console. Scroll up and show it if anyone suspects the flags did the work. `-Xss1m` is
the platform thread's 1 MB stack; virtual threads simply ignore it, which is the point.

**Numbers to expect.** The death point is whatever your machine's thread limit allows —
**2,021 on three consecutive runs** on an 8-core M2, and 2,019–2,021 across thirteen earlier
ones. `kern.num_taskthreads` is 2048 and the JVM's own threads take the remainder, so the
number is strikingly repeatable on a given machine. It varies between machines and that is
fine; the gap to 1,000,000 is the point. Check yours during the warm-up run so you can quote
it confidently.

Both sides are fast. The past side dies in about 0.35 s; the present side creates its million
and prints in about 0.6 s, source-launcher compile included. A million parked virtual threads
settle at roughly 550 MB live inside the 2 GB heap — a comfortable margin, so the present side
is in no danger of dying on memory instead.

Because every thread sleeps for an hour, nothing can retire while the count is still climbing.
The past side's death is a real ceiling rather than a race between creation and completion,
and that holds on any machine, however high its thread limit.

### Demo 3 — Thread-per-Request

**Three snippets, switched like Threads 101.** This is the slide that says the old model was
the *good* design, so the tab states the case rather than knocking it down. The snippets run
the same `findUser → findOrder → chargeCard` chain you will meet again in Demos 4 and 5 — one thread
per request, no callbacks — and between them make all four of the slide's points. The next
tab is where the cost arrives.

**The Handler** — *sequential, readable code* and *ThreadLocal context*.

1. Talk over the code on the left. It reads top to bottom: find the user, find their order,
   charge the card, each call needing the answer from the one before. Point at the
   `ThreadLocal REQUEST_ID` set once at the top of the request.
2. **Run** (`⌘R`). Two requests come back, each in its own colour, because each ran on its
   own thread — *one thread, one request*. They run one after the other, so each colour is
   one whole request.
3. **The request id `[req-42]` / `[req-77]` is on every line — including the three service
   lines**, and nothing passed it to `findUser`, `findOrder` or `chargeCard`. It rode down
   the thread with the call. That is the ThreadLocal point — the same thing SLF4J's MDC and
   a framework's request scope are built on — and it is the one thing no other tab shows.

**ThreadLocal** — *how that context stays per-request.* The obvious objection to step 3:
`REQUEST_ID` is declared `static final`, so there is only one of it — isn't a static field
shared? Switch to this snippet to answer it.

4. **Before you run it**, scroll to `OrderService` and read the signatures out loud:
   `checkout`, `findUser`, `findOrder`, `chargeCard`, `log` — not one of them takes a
   request id. Now **Run**. The two requests overlap: `http-2` arrives 60 ms in, while
   `http-1` is still inside its first database call.
5. Read the `threadlocal` column straight down: every line carries its *own* request's id —
   `req-42` on all three of `http-1`'s lines, `req-77` on all three of `http-2`'s — in a
   class that was never told which request it was serving. Then read the `static` column
   beside it: **`http-1`'s own lines say `req-77`**. One slot, last writer wins — the same
   shared-state race as the Oven in Demo 1, except here it is the wrong customer's id on
   your trace. Finally, go back to the two `@…` values at the top: both threads printed the
   **same** object. One object, two threads, no `synchronized` anywhere — so why did only
   one of those two columns break? Hand that question to the next slide.

**When It Breaks** — *real stack traces* and *trivial to debug*. Switch to it with the
picker (the confirm bar appears first if you have edited the code, exactly like Threads 101).

6. Same handler, but the payment gateway is down and `chargeCard` throws. **Run**. The
   request logs its three services, then `responded 500  (payment gateway timeout)`, then a
   **real** stack trace.
7. Read the trace bottom-up: `callPaymentGateway ← chargeCard ← serve`. Because one thread
   ran the whole request, the trace *is* the whole request — nothing ran on another thread,
   so nothing is missing. And the 500 line still names the request (`[req-42]`), from the
   ThreadLocal, so you know *which* one failed without it being passed to the `catch`.

Both snippets are editable. In **The Handler**, add a method to the chain and re-run to
watch the trace grow (once you make it throw). **Reset** restores the current snippet.

### Demo 4 — Frame by Frame

**The slide of the same name, measured.** Slide 33 draws four boxes — VT-1 mounted on CT-1,
VT-1 unmounted with its stack on the heap, CT-1 picking up somebody else, VT-1 resuming
somewhere different. This tab runs the order service for real and puts the machine's own
numbers into those four sentences.

It calls the same three services demo 5 hits — `findUser`, `findOrder`, `chargeCard` —
directly rather than over HTTP. Say that out loud: *the server is not the subject here; one
request is.* Every request is marked on both sides of every blocking call, and each mark
writes down the instant, the request, and the platform thread actually executing it.

1. **Frame by Frame** tab. Defaults are 24 requests, `GET /order/slow/{id}` (300 ms),
   **Take me to the present**. Press **Run**. It takes about a third of a second.
   - The board fills: one lane per request on top, one lane per OS thread underneath, both
     drawn against the same clock. Pale teal is waiting, solid teal is running.
   - The headline reads roughly **`24 requests · 72 blocking calls · 8 carriers · 0 OS
     threads blocked · 7,600 ms of request time cost 5 ms of carrier time (0.06%) · req-0
     rode 3 carriers`**.
2. **Press `Walk the steps ▶` four times.** The board winds back to the start of the run and
   the four lines appear as they happened:
   ```
   STEP 01   req-0 mounted on worker-5 — calls findUser()
   STEP 02   req-0 unmounted · stack → heap · parked 123.9 ms
   STEP 03   worker-5 free instantly — now carrying req-3. The OS never knew.
   STEP 04   findUser returns after 123.9 ms — req-0 resumes on worker-7, a different carrier.
   ```
   That is the slide, with real carrier names and real milliseconds. Keep clicking: steps
   05–12 are the same thing happening for `findOrder` and `chargeCard`. **Play** walks the
   whole thing on its own.
3. **Press `Show the parked stack`.** Here is the part nobody can argue with: `req-0`'s
   entire stack, captured from outside while it was in the middle of its wait —
   `VirtualThread.parkNanos → sleepNanos → Thread.sleep → OrderService.pause →
   OrderService.findUser`. Every frame is still there. The line underneath reads
   **`OS thread holding this stack: none`**.
4. **Switch to `Take me to the past` and press Run.** Same three calls, same 300 ms, one
   platform thread per request.
   - The board goes orange and every lane is solid from the first call to the last. The
     carrier group collapses into one sentence, because there is nothing new to draw: the
     rows above *are* the OS threads.
   - The headline reads **`24 requests · 72 blocking calls · 24 OS threads · all 24 of them
     blocked · 7,450 ms of thread time, 99.99% of it spent waiting`**.
   - The STEP lines turn orange too, and say the opposite thing:
     **`pool-thread-0 is not free — it belongs to req-0 until the request ends.`**
5. **Press `Show the parked stack` again.** Both panels are now filled and side by side.
   Read them as a pair: almost the same frames, `OrderService.findUser` on both. The
   differences are the top and the bottom — `Thread.sleep0(Native Method)` against
   `VirtualThread.parkNanos`, and `ThreadPoolExecutor.runWorker → Thread.run` against
   nothing at all. The two lines underneath are the whole demo:
   **`OS thread holding this stack: none`** against
   **`OS thread holding this stack: pool-thread-0 (TIMED_WAITING)`**.
   - **`Same frames. Only one of them costs an OS thread.`**

**Then the slide's own punchline lands on its own**: *your code says "wait here" — but
nothing expensive actually waits.*

**Show the code** puts the eight marks on screen, along with how the carrier is read. It is
worth ten seconds, because the honest answer to "how do you even know that?" is short:
`Thread.toString()`. A mounted virtual thread prints
`VirtualThread[#23,req-0]/runnable@ForkJoinPool-1-worker-1`; a parked one prints
`VirtualThread[#23,req-0]/timed_waiting`, with no carrier at all. No agent, no JFR, no
`jdk.internal`.

**Two things to say before someone else does.**

- The solid blocks are *drawn* at a minimum width. Under virtual threads a mounted stretch
  is genuinely microseconds against a wait of hundreds of milliseconds, so at true scale it
  would be a fraction of a pixel and simply would not appear. The caption under the board
  says so, and the real figure is on the right of every row. Exaggerating the running time
  argues *against* the point being made, which is why it is safe to do.
- The record is eight measured instants per request, not a continuous trace. A mount that
  happened between two marks would not show. At these latencies the JDK makes exactly the
  transitions that are marked — one unmount per blocking call.

**Why 24 requests and not 3.** With fewer requests than the machine has cores, nothing is
ever queued for a carrier, so when `req-0` lets go of one, nobody takes it. The step log
says so honestly — *"free instantly — and went idle, because nothing was queued for it"* —
which is true but is not STEP 03. Twenty-four on eight cores guarantees the handover.

### Demo 5 — Performance Comparison

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

### Demo 6 — A Million Lockers

**Two snippets, measured — the `ThreadLocal` leak, and the fix.** This is the late-talk
pitfall (Story 3, "the ThreadLocal memory leak"). A `ThreadLocal` value lives as long as its
**thread** does, not as long as the request does. On a pool of reused worker threads that is
effectively forever: `set()` the context, forget `remove()`, and it stays in that thread's
"locker" after the request is long gone. `ScopedValue` (preview in JDK 21, final in JDK 25)
binds the context to a *block* instead, so it cannot outlive the request. Both snippets run
the same pool of 200 threads serving 10,000 requests.

**ThreadLocal** — the leak.

1. **Run** (`⌘R`). 200 pooled threads serve 10,000 requests, each stashing a ~1 MB context
   and never calling `remove()`. It finishes in a second or two.
2. The punchline is the last line, measured **after the server is idle**: roughly
   **`server idle, 10,000 requests finished: ~400 MB STILL HELD`** — 200 threads doing
   nothing, each still clutching the last context it touched, for requests that finished long
   ago. That is the leak.

**ScopedValue** — the fix. Switch with the picker and **Run** again (the confirm bar appears
first if you have edited the code).

3. Same pool, same 10,000 requests, same ~1 MB context — but bound with
   `ScopedValue.where(...).run(...)`: **`~0 MB still held`**. The console keeps both runs, so
   the two "still held" lines sit one above the other: **~400 MB versus 0**.
4. Say why: the value is reclaimed the instant the `run(...)` block returns — there is no
   `remove()` to forget — and it is immutable, so nothing downstream can reassign it. A
   framework like Spring used to hide the leak by calling `remove()` for you in a `finally`;
   hand-rolled virtual-thread code has no such net. *"Old thread-per-request + ThreadLocal:
   still fine. New virtual-thread code: reach for `ScopedValue`."*

Because `ScopedValue` is a preview here, both snippets run under `--enable-preview --source
21`; the child JVM prints a harmless `Note: … uses preview features` line. The leak lives in
the child JVM, not the app.

### If something goes wrong mid-demo

| Symptom | What to do |
| --- | --- |
| A run seems stuck | `⌘.` (Stop). Tabs 1–3 kill the child JVM; tabs 4 and 5 abandon the run and **keep the previous result on screen**, so you never lose a filled board or panel to a bad run. |
| Console is cluttered | `⌘K`. On tabs 1–3 this also resets the run counter back to #1. On tab 4 it empties the board, the step log and both captured stacks, and on tab 5 both panels and the chart — so on those two, only use it if you mean it. |
| One Oven behaved itself | It is a race, not a guarantee — though it collides on essentially every run. Run it again, or change `i < 3` to `i < 6` in the editor and re-run. |
| Two Cooks produced the same order twice | Say so — it is genuinely random, not guaranteed to differ. Run it once more. Two identical runs followed by a different one makes the point better than a lecture would. |
| Thread-per-Request's trace looks short | It is the real JDK trace, not a trimmed one — the chain is only three of your methods deep. Add a method to the `findUser → findOrder → chargeCard` chain in the editor and re-run to watch it grow by a frame. |
| A Million Lockers ran out of memory | The child JVM asked for more than the machine had. Lower `CONTEXT_KB` or `POOL` in the editor (the leak is `POOL × CONTEXT_KB`), or raise the tab's `-Xmx`. The window stays up regardless — it runs in a child JVM. |
| The workaround beat virtual threads | Expected, and fine. They are the same number and the run-to-run spread is wider than the gap. Say "same speed" and move to the handlers — that is where the argument actually is. |
| Frame by Frame says "went idle" at STEP 03 | The scheduler had a spare carrier, so nobody took the one `req-0` released. It is honest, not broken. Raise **Requests** to 24 and run again — more requests than cores guarantees the handover. |
| Frame by Frame shows a carrier called `carrier?` | This JDK prints `Thread.toString()` in a shape the carrier parser does not recognise. Everything else on the board is still true; the lane names are not. Mention it and move on — or switch to **Take me to the past**, which reads thread names directly and cannot hit this. |
| The parked stack panel says "no snapshot could be captured" | The watcher missed its window. Press **Run** again; it looks for five seconds and the request waits for 120 ms three times, so a second attempt effectively always finds it. The previous stack stays on screen meanwhile. |
| Someone asks why the running blocks look so big | Say it before they do — they are drawn at a minimum width or they would be sub-pixel. Point at the figure on the right of the row: `ran 0.5 of 318.3 ms`. The exaggeration flatters the *wrong* side of the argument. |
| Someone says "you're just measuring Thread.sleep" | Correct, and it is the honest stand-in: `sleep` on a virtual thread unmounts exactly the way a socket read does. The mechanism on screen is the mechanism, not a simulation of it. |
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

### Tabs 1–3 run your code in a different JVM

The past-mode program deliberately exhausts the OS thread limit. Running it in the app's
own process would take the window down with it. Instead the editor's contents are written
to a temp file and executed by a child process using single-file source execution
(`java Demo.java`), under `-Xmx2g -Xss1m` — **the same flags in both modes**. Its stdout
and stderr are merged and streamed back into the console at about 30 Hz.

The child JVM is the runtime bundled inside the `.app`, so nothing needs to be installed
on the presenting machine. That runtime is jlinked **with `jdk.compiler` and `jdk.zipfs`**,
which the source launcher silently requires — `make verify` exists to catch that before it
becomes a problem on stage.

Two output lines are load-bearing: `Died at thread #N of M` and `All N threads alive`. The
app watches for those and promotes them to the punchline banner and the scoreboard. Edit
them live and the demo still runs, you just lose the banner.

### Demo 2's two programs are the same program

`DemoSources` holds **one** template. The two modes are that template with `$FACTORY$`
replaced by `ofPlatform` or `ofVirtual`, so they cannot drift apart — not the comments, not
the prints, not the flags. It would take an edit to the template to make anything else
differ, which is exactly the property the demo is claiming.

That constrains the code in two ways worth knowing before you edit it:

- **The progress print is one shared rule** — one line every thousand threads. Past gets
  two lines before it dies; present gets a thousand of them in a blur. Neither side gets a
  print tuned for itself, and how much scrolls past is itself the comparison.
- **Nothing ever completes.** Every thread sleeps for an hour, so the only number either
  side can report is how many are alive at once. That is deliberate: with a short sleep the
  past side would be measuring a race between creating threads and retiring them, and on a
  generous machine it might never die at all.

The past side's OOM arrives raw and unwrapped out of `Thread.start()` on the main
thread, so the console shows the real
`java.lang.OutOfMemoryError: unable to create native thread` text rather than something the
demo made up. The two `[warning][os,thread]` lines above it are the JVM's own, on stderr.

### Threads 101 compiles ahead of time so re-runs are instant

The source launcher recompiles on every launch, which costs about 250 ms before the first
line of output. That is invisible in Thread Bomb, where the program then runs for seconds.
It is not invisible in a demo whose entire point is pressing Run three times in a row. So
Threads 101 compiles the snippet in the background whenever the editor settles, and Run
then only has to start a JVM: about **25 ms** to the first line instead of 250.
Thread-per-Request shares the same `SourceCompiler`, for the same reason — its whole point
is running it again after a live edit.

If the snippet is not compiled yet, or the presenter has just broken it, the app silently
falls back to the source launcher — same behaviour the other tab has always had, and it is
that fallback that puts the real `javac` error on screen.

Compilation happens in-process through the `javac` `ToolProvider`, so the jlinked runtime
needs `jdk.compiler` — which it already did, for the source launcher. `make verify` checks
both, because neither failure would ever show up in `make run`.

Console lines are tinted by which thread printed them. A name keeps its colour until the
console is cleared, so `Cook#1` looks the same in run #3 as it did in run #1 — otherwise
comparing one run against the one above it would mean nothing. Across all six snippets
exactly three names ever print — `Cook#1`, `Cook#2`, and `main` — which is exactly how many
speaker colours there are. That is why the oven snippets reuse the two cooks rather than
hiring a third: a fourth name wraps around and takes `Cook#1`'s colour. Adding one means
adding `speaker-d` to both the light and dark palettes and to the console rules. The
colours are deliberately neither orange nor teal: those two mean "before Loom" and "after
Loom" everywhere else in the talk, and a `Cook#1` that looked orange would quietly say
something untrue.

The tab finds the speaker with `Threads101Tab.SPEAKER`, which recognises two output shapes:
a thread name followed by `->`, and one followed by `receives` or `serves`. Two Cooks,
Two Cooks, Shared and `run() vs start()` speak in the kitchen; the oven snippets still use
the arrow. A sixth
snippet that invents a third verb runs perfectly well and prints in plain black until that
pattern is taught the word.

### Tab 4 reads the carrier out of `Thread.toString()`

There is no public API for "which OS thread is running this virtual thread", and the demo
does not reach for `jdk.internal.vm.Continuation`, an agent, or JFR to get one. It does not
need to: the JDK already prints it.

```
mounted   VirtualThread[#23,req-0]/runnable@ForkJoinPool-1-worker-1
parked    VirtualThread[#23,req-0]/timed_waiting
```

`FrameRecorder.carrierOf` takes everything after the last `@`, and treats its absence as
"nothing is running this" — which is the whole of STEP 02. A platform thread answers with
its own name, so both eras render on the same board with no special case. If a future
runtime prints a shape the parser cannot read, it answers the literal `carrier?` rather
than guessing: a wrong carrier name would make the board tell a story that did not happen.

Everything else is derived from eight marks per request, taken on the request's own thread
around each of the three blocking calls. `FrameRun` turns those into the lanes, the
percentages, the headline and the STEP lines — one list of events, so the picture and the
sentence under it cannot disagree. It has no JavaFX types in it and can be exercised without
a window, the same bar `TraceView.parse` sets.

**Two claims the tab is careful not to make.**

- The lanes are *not* a continuous trace. Eight instants per request is what was measured,
  and a mount between two of them would be invisible. At these latencies the JDK makes
  exactly the transitions that are marked.
- STEP 03 only says a carrier was picked up by somebody else when a mounted span for
  another request genuinely overlaps the window. Otherwise it says the carrier went idle.
  `FrameRun.focusRequest` prefers a request whose *first* call shows the handover, so that
  steps 01–04 are the slide rather than a near miss.

Under `Era.PAST` the pool is sized to the request count — one platform thread each. That is
deliberately not tab 5's pool of 200: nothing should queue here, because queueing is tab 5's
argument. What is left when nothing queues is exactly what one blocking call costs. The
threads are named `pool-thread-N` rather than `worker-N` so they cannot be confused with the
scheduler's carriers on the same board, which is the one thing this demo must not blur.

`OrderServer` is untouched. Tab 4 calls `OrderService` directly, because a third
instrumented handler shape in that file would quietly cost it the "past and present share a
handler byte for byte" argument it exists to make.

### Why the settings on tab 5 are buttons, not dropdowns

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

### Tab 5 measures a real server

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
