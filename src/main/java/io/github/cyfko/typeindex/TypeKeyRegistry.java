package io.github.cyfko.typeindex;

import io.github.cyfko.typeindex.providers.RegistryProvider;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Global static registry for resolving Java types referenced by a stable logical key.
 *
 * <p>
 * This registry is populated automatically by the {@code TypeIndexProcessor} annotation
 * processor, which generates a {@code RegistryProviderImpl} containing a statically
 * initialized map of keys to Java {@link Class} objects. The registry is exposed at
 * runtime through this {@code TypeRegistry} façade.
 * </p>
 *
 * <p>
 * A typical use case is to store a stable key in a database instead of a fully-qualified
 * class name, thus avoiding breakage if classes are renamed or moved. The {@link TypeKey}
 * annotation ensures that each class explicitly declares its stable identifier, which is
 * then carried into the generated registry.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (TypeRegistry.canResolve("UserDto")) {
 *     Class<?> type = TypeRegistry.resolve("UserDto");
 * }
 *
 * // With type assertion:
 * Class<UserDto> userType = TypeRegistry.resolve("UserDto", UserDto.class);
 *
 * // Reverse lookup from class to key:
 * String key = TypeRegistry.keyOf(UserDto.class);
 * }</pre>
 *
 * <p>
 * The registry is lazily loaded using double-checked locking to minimize overhead
 * and ensure thread safety. A reverse registry (class → key) is also built lazily
 * at first access to support reverse lookup via {@link #keyOf(Class)}.
 * </p>
 */
public final class TypeKeyRegistry {

    private static final Logger log = Logger.getLogger(TypeKeyRegistry.class.getName());

    /**
     * Singleton provider instance generated at compile time.
     * <p>
     * This provider is discovered and instantiated reflectively from the
     * generated {@code RegistryProviderImpl} class.
     * </p>
     */
    private static volatile RegistryProvider PROVIDER;

    /**
     * Reverse mapping of registry entries: Java class → logical type key.
     * <p>
     * This map is initialized lazily when the provider is first loaded and
     * allows looking up the {@link TypeKey} value associated with a given
     * annotated class.
     * </p>
     */
    private static volatile Map<? extends Class<?>, String> REVERTED_REGISTRY;

    private TypeKeyRegistry() {
        // Utility class; not instantiable.
    }

    /**
     * Returns the generated {@link RegistryProvider}.
     *
     * <p>
     * On first invocation, this method:
     * </p>
     * <ol>
     *   <li>Loads {@code RegistryProviderImpl} reflectively.</li>
     *   <li>Initializes the forward registry (key → class).</li>
     *   <li>Builds the reverse registry (class → key) as a defensive copy.</li>
     * </ol>
     *
     * @return The metadata provider exposing the generated registry.
     * @throws IllegalStateException If the generated class cannot be instantiated.
     */
    public static RegistryProvider getRegistryProvider() {
        if (PROVIDER == null) {
            synchronized (TypeKeyRegistry.class) {
                if (PROVIDER == null) {
                    PROVIDER = loadProvider();

                    // Build the inverse lookup map (class -> key) once the provider is available.
                    REVERTED_REGISTRY = PROVIDER.getRegistry()
                            .entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
                }
            }
        }
        return PROVIDER;
    }

    /**
     * Loads the auto-generated {@code RegistryProviderImpl} using reflection.
     *
     * <p>
     * If the generated class cannot be found (for example, because annotation
     * processing was disabled), an error is logged and a no-op provider is
     * returned, exposing an empty registry. This allows the application to
     * start, while still surfacing clear diagnostics.
     * </p>
     *
     * @return A {@link RegistryProvider} backed by the generated registry,
     *         or an empty provider if the generated class is missing.
     */
    private static RegistryProvider loadProvider() {
        try {
            Class<?> cls = Class.forName(
                    "io.github.cyfko.typeindex.providers.RegistryProviderImpl"
            );
            return (RegistryProvider) cls.getConstructor().newInstance();

        } catch (ClassNotFoundException e) {
            log.severe("""
                Cannot load the generated registry.
                Expected generated class: io.github.cyfko.typeindex.providers.RegistryProviderImpl
                Ensure that the annotation processor has run and your build
                system is configured for annotation processing.
                """ + e);
            // Fallback to an empty registry to avoid hard failure at startup.
            return Map::of;

        } catch (InvocationTargetException | InstantiationException |
                 NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to instantiate RegistryProviderImpl", e);
        }
    }

    /**
     * Checks whether the registry contains a class for the given logical key.
     *
     * @param key Logical type identifier; must not be {@code null}.
     * @return {@code true} if the registry contains a mapping for this key;
     *         {@code false} otherwise.
     * @throws NullPointerException If {@code key} is {@code null}.
     */
    public static boolean canResolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return getRegistryProvider().getRegistry().containsKey(key);
    }

    /**
     * Resolves a type by its logical key.
     *
     * @param key Logical type identifier; must not be {@code null}.
     * @return The class associated with the given key.
     * @throws NullPointerException  If {@code key} is {@code null}.
     * @throws IllegalStateException If no class is mapped for the given key.
     */
    public static Class<?> resolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        Class<?> type = getRegistryProvider().getRegistry().get(key);

        if (type == null) {
            throw new IllegalStateException("No type mapped for key: " + key);
        }

        return type;
    }

    /**
     * Resolves the type by key and verifies that it matches the expected class.
     *
     * <p>
     * This overload provides an additional safety check: it ensures the registry
     * entry for {@code key} is exactly {@code targetType}. If the mapping does
     * not match, an {@link IllegalArgumentException} is thrown.
     * </p>
     *
     * @param key        Logical type identifier; must not be {@code null}.
     * @param targetType Expected concrete class; must not be {@code null}.
     * @param <T>        The expected type parameter.
     * @return The {@code targetType} if the mapping is valid.
     * @throws NullPointerException     If {@code key} or {@code targetType} is {@code null}.
     * @throws IllegalArgumentException If the registry mapping does not match {@code targetType}.
     */
    public static <T> Class<T> resolve(String key, Class<T> targetType) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(targetType, "targetType cannot be null");

        Class<?> mapped = getRegistryProvider().getRegistry().get(key);

        if (!targetType.equals(mapped)) {
            throw new IllegalArgumentException(
                    "Registry mismatch for key '" + key + "'. Expected: "
                            + targetType.getName() + ", found: " + mapped
            );
        }

        return targetType;
    }

    /**
     * Returns the logical {@link TypeKey} value associated with the given annotated class.
     *
     * <p>
     * This method performs the reverse lookup: given a class that was annotated
     * with {@code @TypeKey}, it returns the stable key that was used in the
     * generated registry. This is useful when you need to persist or publish
     * the key corresponding to a known type.
     * </p>
     *
     * @param annotatedClass Class annotated with {@link TypeKey}; must not be {@code null}.
     * @return The stable logical key associated with {@code annotatedClass}.
     * @throws NullPointerException  If {@code annotatedClass} is {@code null}.
     * @throws IllegalStateException If no key is mapped for the given class
     *                               (for example, if the class is not annotated
     *                               with {@code @TypeKey}, or annotation processing
     *                               did not generate a registry for it).
     */
    public static String keyOf(Class<?> annotatedClass) {
        Objects.requireNonNull(annotatedClass, "annotatedClass cannot be null");
        getRegistryProvider(); // Ensure provider and reverse registry are initialized.

        if (!REVERTED_REGISTRY.containsKey(annotatedClass)) {
            throw new IllegalStateException(
                    "No key mapped for: " + annotatedClass.getCanonicalName()
            );
        }

        return REVERTED_REGISTRY.get(annotatedClass);
    }
}

