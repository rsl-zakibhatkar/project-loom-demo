package loomdemo.load;

import loomdemo.Era;
import loomdemo.Shape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * One recorded run, and every number the Frame by Frame tab puts on screen.
 *
 * <p>Everything here is derived from {@link MountEvent}s — the lanes, the percentages, the
 * headline and the STEP lines all come out of the same list, so the board cannot disagree
 * with the sentence underneath it. Deliberately free of any JavaFX type, for the same reason
 * {@code TraceView.parse} is: this is the part with the interesting edge cases, and it should
 * not need a window to exercise.
 *
 * <p><strong>What the marks can and cannot see.</strong> A request is marked on both sides of
 * every blocking call, so a mount or unmount happening <em>between</em> two marks would be
 * invisible here. At these latencies the JDK makes exactly the transitions that are marked —
 * one unmount per blocking call — and the tab says so in a tooltip rather than pretending the
 * record is a continuous trace. What it is, is the truth at eight instants per request.
 */
public final class FrameRun {

    /** How the three services are named on screen, in call order. */
    public static final List<String> SERVICES = List.of("findUser", "findOrder", "chargeCard");

    /**
     * One stretch of a request's life: either mounted and running, or waiting.
     *
     * @param carrier  the platform thread running it, or null while it waits — under virtual
     *                 threads nothing is running it at all, and under platform threads the
     *                 thread is parked in the kernel rather than executing
     * @param pinnedOn the carrier this request was still sitting on <em>while waiting</em>,
     *                 or null — which is almost always. Non-null only when the sweep caught
     *                 the thread {@code TIMED_WAITING} with a carrier still attached, a state
     *                 nothing but a pinned virtual thread can be in. It is deliberately a
     *                 separate component from {@code carrier}: a wait is a wait either way,
     *                 and what differs is whether an OS thread was stuck inside it.
     */
    public record Span(int request, String carrier, long startNanos, long endNanos,
                       boolean mounted, String pinnedOn) {
        public long durationNanos() {
            return endNanos - startNanos;
        }

        /** Waiting, and holding a carrier the whole time. */
        public boolean pinned() {
            return pinnedOn != null;
        }

        boolean overlaps(long from, long to) {
            return startNanos < to && endNanos > from;
        }
    }

    /**
     * One line of the step log, and the instant it describes.
     *
     * <p>{@code styleClass} says what the line <em>claims</em>, never which era produced it:
     * {@link #PLAIN} for narration, {@link #GOOD} for something that turned out to be free,
     * {@link #COST} for something that had to be paid for. The same sentence position is good
     * news under virtual threads and bad news under platform threads, so colouring by era
     * would put the wrong colour on half the log.
     */
    public record Step(int number, long nanos, String text, String styleClass) {
    }

    /** Narration: where the request is, what it is about to call. */
    public static final String PLAIN = "step-plain";
    /** Something cost nothing — a thread let go, a carrier reused, a resume elsewhere. */
    public static final String GOOD = "step-good";
    /** Something was held: an OS thread parked in the kernel, a thread nobody else can have. */
    public static final String COST = "step-cost";

    /**
     * A stack captured from outside, while its owner was waiting.
     *
     * @param holder what is holding the stack up — a named OS thread, or nothing at all
     * @param held   whether an OS thread was holding it, which is the entire exhibit
     */
    public record ParkedStack(int request, String header, String stack, String holder,
                              boolean held) {
    }

    /**
     * One virtual thread that asked for nothing, and when it finally got to run.
     *
     * <p>It takes no lock and does no work, so {@link #queuedNanos()} is time it spent
     * waiting for a carrier and nothing else. That is slide 40's "millions ready to run, no
     * carrier free", reduced to one measurable number.
     */
    public record Probe(long submittedNanos, long startedNanos, String carrier) {

        /** Above this it plainly waited, rather than merely taking a moment to be scheduled. */
        private static final long STARVED_MILLIS = 5;

        public long queuedNanos() {
            return Math.max(0, startedNanos - submittedNanos);
        }

        /**
         * Whether it had to wait for a carrier at all.
         *
         * <p>The one honest test of "the whole service stops". Every request in the run can
         * finish on time and this can still be true — at one request per carrier nothing
         * queues and nothing looks wrong, right up until a ninth request arrives and finds
         * there is nothing left to run it on.
         */
        public boolean starved() {
            return queuedNanos() >= STARVED_MILLIS * 1_000_000;
        }
    }

    private final Shape shape;
    private final int requests;
    private final int latencyMillis;
    private final List<MountEvent> events;
    private final ParkedStack parked;

    private final Map<Integer, List<MountEvent>> byRequest = new TreeMap<>();
    private final List<Span> spans = new ArrayList<>();
    private final List<String> carriers;
    private final List<String> pinnedCarriers;
    private final Probe probe;
    private final long t0;
    private final long t1;

    public FrameRun(Shape shape, int requests, int latencyMillis, List<MountEvent> events,
                    List<PinSample> samples, ParkedStack parked, Probe probe) {
        this.shape = shape;
        this.requests = requests;
        this.latencyMillis = latencyMillis;
        this.parked = parked;
        this.probe = probe;

        List<MountEvent> sorted = new ArrayList<>(events);
        sorted.sort((a, b) -> Long.compare(a.nanos(), b.nanos()));
        this.events = List.copyOf(sorted);

        for (MountEvent event : this.events) {
            byRequest.computeIfAbsent(event.request(), key -> new ArrayList<>()).add(event);
        }
        Map<Integer, List<PinSample>> samplesByRequest = new TreeMap<>();
        for (PinSample sample : samples) {
            samplesByRequest.computeIfAbsent(sample.request(), key -> new ArrayList<>())
                    .add(sample);
        }

        for (List<MountEvent> perRequest : byRequest.values()) {
            for (int i = 0; i + 1 < perRequest.size(); i++) {
                MountEvent a = perRequest.get(i);
                MountEvent b = perRequest.get(i + 1);
                boolean mounted = !a.startsWait();
                // A wait is pinned when the sweep actually caught this request waiting with a
                // carrier still under it. Nothing here asks which button was pressed — an
                // unguarded run simply produces no samples and comes out unpinned, the same
                // way threadPerRequest() is a property of what happened rather than a setting.
                String pinnedOn = mounted ? null
                        : heldDuring(samplesByRequest.get(a.request()), a.nanos(), b.nanos());
                spans.add(new Span(a.request(), mounted ? a.carrier() : null,
                        a.nanos(), b.nanos(), mounted, pinnedOn));
            }
        }

        // First-seen order, so the lanes appear in the order the run actually touched them
        // rather than in whatever order a hash gave us.
        Set<String> seen = new LinkedHashSet<>();
        for (MountEvent event : this.events) {
            if (event.carrier() != null) {
                seen.add(event.carrier());
            }
        }
        this.carriers = List.copyOf(seen);

        Set<String> held = new LinkedHashSet<>();
        for (Span span : spans) {
            if (span.pinned()) {
                held.add(span.pinnedOn());
            }
        }
        this.pinnedCarriers = List.copyOf(held);

        this.t0 = this.events.isEmpty() ? 0 : this.events.get(0).nanos();
        this.t1 = this.events.isEmpty() ? 0 : this.events.get(this.events.size() - 1).nanos();
    }

    // ------------------------------------------------------------------ shape

    /** What the run was made of. The colours and the panels read {@link #era()} instead. */
    public Shape shape() {
        return shape;
    }

    public Era era() {
        return shape.era();
    }

    public int requests() {
        return requests;
    }

    public int latencyMillis() {
        return latencyMillis;
    }

    public List<MountEvent> events() {
        return events;
    }

    public ParkedStack parked() {
        return parked;
    }

    /** The do-nothing request, or null when this shape did not submit one. */
    public Probe probe() {
        return probe;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public long t0() {
        return t0;
    }

    public long totalNanos() {
        return Math.max(1, t1 - t0);
    }

    /** Request indices that actually recorded something, in order. */
    public List<Integer> requestIds() {
        return List.copyOf(byRequest.keySet());
    }

    public List<MountEvent> eventsFor(int request) {
        return byRequest.getOrDefault(request, List.of());
    }

    public List<Span> spansFor(int request) {
        return spans.stream().filter(span -> span.request() == request).toList();
    }

    /** Distinct carriers touched, in first-seen order. Under PAST these are the threads. */
    public List<String> carriers() {
        return carriers;
    }

    /** Whether any request was caught waiting without being able to let go of its carrier. */
    public boolean pinned() {
        return !pinnedCarriers.isEmpty();
    }

    /** Carriers that were held by a waiting request, in first-seen order. */
    public List<String> pinnedCarriers() {
        return pinnedCarriers;
    }

    public List<Span> pinnedSpansOn(String carrier) {
        return spans.stream().filter(span -> carrier.equals(span.pinnedOn())).toList();
    }

    public long pinnedNanosOn(String carrier) {
        return pinnedSpansOn(carrier).stream().mapToLong(Span::durationNanos).sum();
    }

    /** Carrier time lost to waiting, summed. Zero unless something was pinned. */
    public long pinnedNanos() {
        return spans.stream().filter(Span::pinned).mapToLong(Span::durationNanos).sum();
    }

    /**
     * How many passes it took to get through the requests.
     *
     * <p>Only interesting when carriers are being held: with nothing pinned every request is
     * in flight at once and this is a number about a queue that did not form.
     */
    public int waves() {
        return carriers.isEmpty() ? 0 : (requests + carriers.size() - 1) / carriers.size();
    }

    public List<Span> mountedSpansOn(String carrier) {
        return spans.stream()
                .filter(Span::mounted)
                .filter(span -> carrier.equals(span.carrier()))
                .toList();
    }

    /** How many distinct carriers one request ran on. One means it never moved. */
    public int hops(int request) {
        Set<String> touched = new LinkedHashSet<>();
        for (Span span : spansFor(request)) {
            if (span.mounted() && span.carrier() != null) {
                touched.add(span.carrier());
            }
        }
        return touched.size();
    }

    /**
     * True when every request had a thread to itself and every thread had one request.
     *
     * <p>That is what platform threads do, and it means the carrier rows would be a
     * row-for-row copy of the request rows above them — the same twenty-four lanes drawn
     * twice, pushing everything else off the screen. The board says it in a sentence
     * instead. Derived rather than asked of the era, because it is a property of what the
     * run did: a virtual-thread run that happened to come out 1:1 would be the same picture
     * and deserves the same treatment.
     */
    public boolean threadPerRequest() {
        if (carriers.size() != byRequest.size()) {
            return false;
        }
        for (String carrier : carriers) {
            if (mountedSpansOn(carrier).stream().mapToInt(Span::request).distinct().count() != 1) {
                return false;
            }
        }
        for (int request : byRequest.keySet()) {
            if (hops(request) != 1) {
                return false;
            }
        }
        return true;
    }

    public long mountedNanos() {
        return spans.stream().filter(Span::mounted).mapToLong(Span::durationNanos).sum();
    }

    public long waitingNanos() {
        return spans.stream().filter(span -> !span.mounted())
                .mapToLong(Span::durationNanos).sum();
    }

    /** Time a request existed at all, summed — under PAST this is OS-thread-seconds held. */
    public long heldNanos() {
        return mountedNanos() + waitingNanos();
    }

    /**
     * Threads the operating system had to keep, and park, for the duration of a wait.
     *
     * <p>Under virtual threads this is zero by construction and that is the claim; under
     * platform threads it is one per request, because that is what a blocking call costs.
     */
    public int osThreadsBlocked() {
        if (pinned()) {
            // Present era, and yet: every held carrier is an OS thread parked in the kernel
            // for the length of a wait. Answering 0 here because the era says PRESENT would
            // put the one claim this run exists to disprove into its own headline.
            return pinnedCarriers.size();
        }
        return era() == Era.PRESENT ? 0 : carriers.size();
    }

    private static String heldDuring(List<PinSample> samples, long from, long to) {
        if (samples == null) {
            return null;
        }
        for (PinSample sample : samples) {
            if (sample.nanos() >= from && sample.nanos() < to) {
                return sample.carrier();
            }
        }
        return null;
    }

    /** The carrier this request was pinned to across one call, or null if it unmounted. */
    private String pinnedOnBetween(int request, long from, long to) {
        for (Span span : spansFor(request)) {
            if (!span.mounted() && span.startNanos() == from && span.endNanos() == to) {
                return span.pinnedOn();
            }
        }
        return null;
    }

    /** How many carriers were held by <em>somebody</em> at any point in this window. */
    private int pinnedCarriersDuring(long from, long to) {
        Set<String> held = new LinkedHashSet<>();
        for (Span span : spans) {
            if (span.pinned() && span.overlaps(from, to)) {
                held.add(span.pinnedOn());
            }
        }
        return held.size();
    }

    // ------------------------------------------------------------------ narration

    /**
     * Which request the STEP lines follow.
     *
     * <p>Prefers one that shows the whole story — it moved between carriers <em>and</em> a
     * carrier it released was picked up by somebody else — so that STEP 03 and STEP 04 are
     * about something that happened rather than something that did not. Falls back to any
     * request that at least hopped, and then to the first request, whose steps will honestly
     * report standing still.
     */
    public int focusRequest() {
        List<Integer> ids = requestIds();
        if (ids.isEmpty()) {
            return 0;
        }
        int anyReuse = -1;
        int hopped = -1;
        for (int request : ids) {
            boolean hops = hops(request) > 1;
            if (hops && hopped < 0) {
                hopped = request;
            }
            if (!hops) {
                continue;
            }
            if (reuseOnCall(request, 0)) {
                // The best case, and the only one worth searching for: this request's FIRST
                // blocking call is the one somebody else took the carrier during. Steps 01-04
                // are the slide, so the reuse has to happen inside them rather than later.
                return request;
            }
            if (anyReuse < 0 && hasReuse(request)) {
                anyReuse = request;
            }
        }
        if (anyReuse >= 0) {
            return anyReuse;
        }
        return hopped >= 0 ? hopped : ids.get(0);
    }

    /** Whether call number {@code index} (0-based) had its carrier taken while it waited. */
    private boolean reuseOnCall(int request, int index) {
        int seen = 0;
        for (MountEvent call : eventsFor(request)) {
            if (call.kind() != MountEvent.Kind.CALL) {
                continue;
            }
            if (seen++ == index) {
                return reuserDuring(call.carrier(), call.nanos(),
                        waitEndFor(request, call), request).isPresent();
            }
        }
        return false;
    }

    private boolean hasReuse(int request) {
        for (MountEvent call : eventsFor(request)) {
            if (call.kind() == MountEvent.Kind.CALL
                    && reuserDuring(call.carrier(), call.nanos(), waitEndFor(request, call),
                            request).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private long waitEndFor(int request, MountEvent call) {
        List<MountEvent> perRequest = eventsFor(request);
        int index = perRequest.indexOf(call);
        return index >= 0 && index + 1 < perRequest.size()
                ? perRequest.get(index + 1).nanos()
                : call.nanos();
    }

    /**
     * Somebody else running on {@code carrier} while {@code request} was waiting on it.
     *
     * <p>This is STEP 03, and it is only ever claimed when it is in the record. With three
     * requests over eight carriers nobody is queued for a carrier, nothing reuses it, and the
     * step has to say so — which is why the tab defaults to more requests than the machine
     * has cores.
     */
    private OptionalInt reuserDuring(String carrier, long from, long to, int excludeRequest) {
        if (carrier == null) {
            return OptionalInt.empty();
        }
        for (Span span : mountedSpansOn(carrier)) {
            if (span.request() != excludeRequest && span.overlaps(from, to)) {
                return OptionalInt.of(span.request());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The step log: four lines per blocking call, in the order slide 33 draws them, with the
     * measured values substituted in. Twelve lines for three calls — the first four are the
     * slide, and the rest are the same thing happening twice more.
     */
    public List<Step> steps() {
        List<Step> out = new ArrayList<>();
        int request = focusRequest();
        List<MountEvent> perRequest = eventsFor(request);
        boolean virtual = era() == Era.PRESENT;
        int number = 0;

        for (int i = 0; i + 1 < perRequest.size(); i++) {
            MountEvent call = perRequest.get(i);
            if (call.kind() != MountEvent.Kind.CALL) {
                continue;
            }
            MountEvent ret = perRequest.get(i + 1);
            String from = shortCarrier(call.carrier());
            String to = shortCarrier(ret.carrier());
            String waited = millis(ret.nanos() - call.nanos());
            OptionalInt reuser = reuserDuring(call.carrier(), call.nanos(), ret.nanos(), request);

            // The same four slots as below — where it is, what the block cost, what happened
            // to the carrier, what resuming looked like — with every answer inverted. STEP 02
            // and STEP 03 are the two the slide is about.
            String pin = pinnedOnBetween(request, call.nanos(), ret.nanos());
            if (pin != null) {
                String held = shortCarrier(pin);
                out.add(new Step(++number, call.nanos(), String.format(Locale.US,
                        "req-%d mounted on %s — inside synchronized — calls %s()",
                        request, held, call.service()), PLAIN));
                out.add(new Step(++number, call.nanos(), String.format(Locale.US,
                        "req-%d blocks · CANNOT unmount, it holds a monitor · pinned to %s "
                                + "for %s ms", request, held, waited), COST));
                out.add(new Step(++number, ret.nanos(), String.format(Locale.US,
                        "%s is not free — req-%d is asleep on it, and %d of %d carriers are "
                                + "held like this right now.", held, request,
                        pinnedCarriersDuring(call.nanos(), ret.nanos()), carriers.size()),
                        COST));
                out.add(new Step(++number, ret.nanos(), String.format(Locale.US,
                        "%s returns after %s ms — req-%d carries on, on the carrier it never "
                                + "let go of.", call.service(), waited, request), PLAIN));
                continue;
            }

            out.add(new Step(++number, call.nanos(), virtual
                    ? String.format(Locale.US, "req-%d mounted on %s — calls %s()",
                            request, from, call.service())
                    : String.format(Locale.US, "req-%d running on %s — calls %s()",
                            request, from, call.service()),
                    PLAIN));

            out.add(new Step(++number, call.nanos(), virtual
                    ? String.format(Locale.US,
                            "req-%d unmounted · stack → heap · parked %s ms", request, waited)
                    : String.format(Locale.US,
                            "req-%d blocks · %s parks in the kernel · TIMED_WAITING %s ms",
                            request, from, waited),
                    virtual ? GOOD : COST));

            String carrierLine;
            if (reuser.isPresent()) {
                carrierLine = String.format(Locale.US,
                        "%s free instantly — now carrying req-%d. The OS never knew.",
                        from, reuser.getAsInt());
            } else if (virtual) {
                carrierLine = String.format(Locale.US,
                        "%s free instantly — and went idle, because nothing was queued for it.",
                        from);
            } else {
                carrierLine = String.format(Locale.US,
                        "%s is not free — it belongs to req-%d until the request ends.",
                        from, request);
            }
            out.add(new Step(++number, ret.nanos(), carrierLine, virtual ? GOOD : COST));

            String resumeLine;
            if (!virtual) {
                resumeLine = String.format(Locale.US,
                        "%s returns after %s ms — req-%d carries on, same thread throughout.",
                        call.service(), waited, request);
            } else if (to.equals(from)) {
                resumeLine = String.format(Locale.US,
                        "%s returns after %s ms — req-%d resumes on %s, the carrier it left.",
                        call.service(), waited, request, to);
            } else {
                resumeLine = String.format(Locale.US,
                        "%s returns after %s ms — req-%d resumes on %s, a different carrier.",
                        call.service(), waited, request, to);
            }
            out.add(new Step(++number, ret.nanos(), resumeLine,
                    virtual && !to.equals(from) ? GOOD : PLAIN));
        }
        return out;
    }

    /**
     * The sentence under the board.
     *
     * <p>Both halves come off the same spans the lanes are drawn from, so if the percentage
     * ever disagrees with the picture, the derivation is wrong and not the wording.
     */
    public String headline() {
        long mounted = mountedNanos();
        long waiting = waitingNanos();
        long held = mounted + waiting;
        double busyPercent = held == 0 ? 0 : 100.0 * mounted / held;
        int calls = requests * SERVICES.size();

        if (pinned()) {
            // Wall clock rather than a busy percentage. A pinned carrier is 99% idle by the
            // measure the present-era line uses, and saying so would read as good news.
            return String.format(Locale.US,
                    "%,d requests  ·  %,d blocking calls  ·  %d carriers  ·  ALL %d PINNED"
                            + "  ·  %s ms of wall clock for %d ms of latency — %s of %d"
                            + "  ·  carriers ran %s ms and were HELD %s ms",
                    requests, calls, carriers.size(), pinnedCarriers.size(),
                    millis(totalNanos()), latencyMillis,
                    waves() == 1 ? "1 wave" : waves() + " waves", carriers.size(),
                    millis(mounted), millis(pinnedNanos()));
        }

        if (era() == Era.PRESENT) {
            return String.format(Locale.US,
                    "%,d requests  ·  %,d blocking calls  ·  %d carriers  ·  "
                            + "0 OS threads blocked  ·  %s ms of request time cost %s ms of "
                            + "carrier time (%.2f%%)  ·  req-%d rode %d carriers",
                    requests, calls, carriers.size(),
                    millis(held), millis(mounted), busyPercent,
                    focusRequest(), hops(focusRequest()));
        }
        return String.format(Locale.US,
                "%,d requests  ·  %,d blocking calls  ·  %d OS threads  ·  all %d of them "
                        + "blocked  ·  %s ms of thread time, %.2f%% of it spent waiting",
                requests, calls, carriers.size(), osThreadsBlocked(),
                millis(held), 100.0 - busyPercent);
    }

    // ------------------------------------------------------------------ formatting

    /**
     * {@code ForkJoinPool-1-worker-3} is the truth and {@code worker-3} is what fits on a
     * lane label. The full name stays in the tooltips, so nothing is hidden — this is only
     * about the width of a row.
     *
     * <p>Only the scheduler's own pool is shortened. The platform-thread names this app
     * chooses are already short, and shortening them too would leave both eras printing
     * {@code worker-N} — two entirely different things wearing one name, in the one demo
     * whose whole subject is telling them apart.
     */
    public static String shortCarrier(String carrier) {
        if (carrier == null) {
            return "—";
        }
        if (!carrier.startsWith("ForkJoinPool")) {
            return carrier;
        }
        int worker = carrier.indexOf("-worker-");
        return worker >= 0 ? carrier.substring(worker + 1) : carrier;
    }

    public static String millis(long nanos) {
        return String.format(Locale.US, "%,.1f", nanos / 1_000_000.0);
    }
}
