package io.github.cyfko.typeindex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a stable, logical business identifier for a Java type.
 *
 * <p>
 * {@code @TypeKey} is used in conjunction with the TypeIndex annotation processor
 * to generate a compile-time registry that maps stable keys to concrete Java
 * classes (classes, records, or enums). Instead of persisting or exchanging fully
 * qualified class names, applications can rely on these stable keys, making
 * refactorings (renames, package moves) safe for persisted data and external integrations.
 * </p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>The {@linkplain #value() key} is a <strong>business identifier</strong>,
 *       not a technical Java name.</li>
 *   <li>Keys must be <strong>unique</strong> across all registered types within
 *       the compilation unit.</li>
 *   <li>Keys must be treated as <strong>immutable public API</strong>:
 *       once a key is used in production (e.g. stored in a database or exchanged
 *       over APIs), it must never change.</li>
 * </ul>
 *
 * <h2>Validation Rules</h2>
 * <p>
 * The TypeIndex processor enforces the following constraints at compile time:
 * </p>
 * <ul>
 *   <li>{@code @TypeKey} can only be applied to classes, records, or enums, or to methods within a {@link TypeKeyConfig} interface.</li>
 *   <li>The key value must not be {@code null} or blank.</li>
 *   <li>The key may contain only: alphanumeric characters and {@code '.'}, {@code '-'}, {@code '#'}, {@code '_'}.</li>
 *   <li>Keys must be globally unique; duplicate keys will cause compilation to fail.</li>
 * </ul>
 *
 * <h2>Alternative: Non-Intrusive External Configuration</h2>
 * <p>
 * If you cannot or do not want to annotate your classes directly (e.g. in Hexagonal Architecture, Clean Architecture, or Domain-Driven Design
 * where domain classes must remain free of infrastructure annotations), use {@link TypeKeyConfig}
 * on an interface to declare external type-key mappings.
 * </p>
 *
 * <h2>Basic Type Registration</h2>
 * <pre>{@code
 * @TypeKey("user-dto")
 * public final class UserDto {
 *     private String username;
 *     private String email;
 * }
 *
 * @TypeKey("order-created-event")
 * public record OrderCreatedEvent(String orderId, LocalDateTime timestamp) { }
 *
 * @TypeKey("order-status")
 * public enum OrderStatus {
 *     PENDING, CONFIRMED, SHIPPED, DELIVERED
 * }
 * }</pre>
 *
 * <h2>Resolving Types at Runtime</h2>
 * <pre>{@code
 * // Check if type exists
 * if (TypeKeyRegistry.canResolve("user-dto")) {
 *     Class<?> type = TypeKeyRegistry.resolve("user-dto");
 * }
 *
 * // Resolve with type safety
 * Class<UserDto> userType = TypeKeyRegistry.resolve("user-dto", UserDto.class);
 *
 * // Reverse lookup
 * String key = TypeKeyRegistry.keyOf(UserDto.class); // Returns "user-dto"
 * }</pre>
 *
 * @see io.github.cyfko.typeindex.TypeKeyRegistry
 * @see io.github.cyfko.typeindex.TypeKeyConfig
 * @author Frank KOSSI
 * @since 1.0.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface TypeKey {

    /**
     * Stable business identifier associated with the annotated type or method.
     *
     * <p>
     * This value is registered in the generated type registry and can be used
     * to look up the class at runtime via {@link io.github.cyfko.typeindex.TypeKeyRegistry#resolve(String)}.
     * </p>
     *
     * <p>
     * The key must:
     * </p>
     * <ul>
     *   <li>Not be {@code null} or blank</li>
     *   <li>Contain only alphanumeric characters and {@code '.'}, {@code '-'},
     *       {@code '#'}, {@code '_'}</li>
     *   <li>Be globally unique across all registered types (enforced at compile time)</li>
     *   <li>Remain stable once used in production (never change)</li>
     * </ul>
     *
     * @return the stable logical key for this type or method
     */
    String value();
}
