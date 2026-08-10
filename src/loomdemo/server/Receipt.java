package loomdemo.server;

/** What the third service returns. See {@link User} for why these are top-level. */
public record Receipt(String orderId, String sentTo, String status) {
}
