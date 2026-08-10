package loomdemo.server;

/**
 * A customer, as far as this demo is concerned.
 *
 * <p>Top-level rather than nested inside a service so the handler source the audience reads
 * says {@code User user = ...} and not {@code OrderService.User user = ...}. The whole point
 * of these three records is that the handler looks like a controller they have written.
 */
public record User(String id, String email) {
}
