package loomdemo.server;

/**
 * What the third service returns, built from the authorisation code the payment gateway
 * hands back. See {@link User} for why these are top-level.
 */
public record Receipt(String orderId, String sentTo, String authCode) {
}
