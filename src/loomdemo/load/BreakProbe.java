package loomdemo.load;

import loomdemo.server.OrderServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * One request to the endpoint that always fails, for the comparison tab's "Break it" view.
 *
 * <p>Deliberately not part of {@link LoadGenerator}. Nothing here is measured, sampled,
 * warmed up or timed — it is a single GET whose only interesting product is the response
 * body, which is the server's own stack trace. Putting it through the load generator would
 * mean dragging a semaphore, a latency array and a sample timeline along for one request.
 *
 * <p>It lives beside the load generator rather than in the UI because it is the only other
 * thing in the app that speaks HTTP, and it has to make the same choice about protocol
 * version for the same reason.
 */
public final class BreakProbe {

    private BreakProbe() {
    }

    /**
     * Fetch the stack trace the server produces for a deliberate failure.
     *
     * <p>Any non-200 is expected — the endpoint's whole job is to fail, and it answers 500
     * with the trace as the body. The status is therefore ignored and the body returned as
     * it arrived.
     *
     * @return the raw response body, never {@code null}
     * @throws Exception if the request could not be made at all, which is a real problem
     *                   and belongs on screen rather than swallowed
     */
    public static String fetchTrace(int port, Duration timeout) throws Exception {
        // HTTP/1.1 pinned for the same reason LoadGenerator pins it: com.sun.net.httpserver
        // speaks nothing else.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(OrderServer.boomUrl(port)))
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        return body == null ? "" : body;
    }
}
