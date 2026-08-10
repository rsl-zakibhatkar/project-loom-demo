package loomdemo.server;

/** An order belonging to a {@link User}. See {@link User} for why these are top-level. */
public record Order(String id, String userId, String status) {
}
