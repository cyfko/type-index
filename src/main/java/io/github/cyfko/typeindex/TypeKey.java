package io.github.cyfko.typeindex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a stable, logical business identifier for a Java type.
 * <p>
 * {@code @TypeKey} is used in conjunction with the TypeIndex annotation processor
 * to generate a compile-time registry that maps stable keys to concrete Java
 * classes. Instead of persisting or exchanging fully qualified class names,
 * applications can rely on these stable keys, making refactorings (renames,
 * package moves) safe for persisted data and external integrations.
 * </p>
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>The {@linkplain #value() key} is a <strongbusiness identifier</strong>,
 *       not a technical Java name.</li>
 *   <li>Keys must be <strong>unique</strong> across all annotated types within
 *       the compilation unit processed by the annotation processor.</li>
 *   <li>Keys must be treated as <strong>immutable public API</strong>:
 *       once a key is used in production (e.g. stored in a database or exchanged
 *       over APIs), it must never change.</li>
 * </ul>
 *
 * <h3>Validation Rules</h3>
 * <p>
 * The TypeIndex processor enforces the following constraints at compile time:
 * </p>
 * <ul>
 *   <li>{@code @TypeKey} can only be applied to classes.</li>
 *   <li>The key value must not be {@code null} or blank.</li>
 *   <li>The key may contain only:
 *       alphanumeric characters and {@code '.'}, {@code '-'}, {@code '#'}, {@code '_' }.</li>
 *   <li>Keys must be globally unique; any duplicate key will cause compilation to fail,
 *       with diagnostics pointing to both conflicting declarations.</li>
 * </ul>
 *
 * <h3>Retention and Processing</h3>
 * <ul>
 *   <li>The retention policy is {@link RetentionPolicy#SOURCE}, which means the
 *       annotation is available only during compilation.</li>
 *   <li>The runtime registry is populated exclusively through generated code
 *       (e.g. {@code RegistryProviderImpl}); {@code @TypeKey} is not visible
 *       via reflection at runtime.</li>
 * </ul>
 *
 * <h3>Typical Usage</h3>
 * <pre>{@code
 * @TypeKey("UserDto")
 * public final class UserDto {
 *     // ...
 * }
 *
 * @TypeKey("Order.Created")
 * public final class OrderCreatedEvent {
 *     // ...
 * }
 * }</pre>
 *
 * <h3>Integration with TypeRegistry</h3>
 * <p>
 * After compilation, the generated registry can be accessed via {@code TypeRegistry}:
 * </p>
 * <pre>{@code
 * if (TypeRegistry.canResolve("UserDto")) {
 *     Class<?> type = TypeRegistry.resolve("UserDto");
 * }
 *
 * Class<UserDto> userType =
 *         TypeRegistry.resolve("UserDto", UserDto.class);
 * }</pre>
 *
 * <p>
 * This pattern decouples persisted and external contracts from Java implementation
 * details while keeping type resolution centralized and explicit.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface TypeKey {

    /**
     * Stable business identifier associated with the annotated class.
     * <p>
     * This value is what will be persisted, exchanged, and used as the lookup
     * key in the generated type registry. It must comply with the allowed
     * character set and remain stable once used in production.
     * </p>
     *
     * @return the stable logical key for this type
     */
    String value();
}

