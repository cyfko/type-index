package io.github.cyfko.typeindex;

import io.github.cyfko.typeindex.providers.RegistryProvider;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Global static registry for resolving Java types by a stable logical key.
 *
 * <p>
 * Populated at build time by an annotation processor that generates a {@code RegistryProviderImpl}
 * containing a static map of keys to {@link Class} objects. At runtime, this class exposes that
 * registry and offers controlled fallbacks for primitives and classpath types when resolving keys.
 * </p>
 *
 * <h2>Why</h2>
 * <p>
 * Persist and exchange stable logical identifiers instead of fully-qualified class names to keep
 * stored data resilient to refactors (renames, package moves). Classes participating in the
 * application contract should declare {@link TypeKey}, which is validated and indexed at compile time.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (TypeKeyRegistry.canResolve("UserDto")) {
 *     Class<?> type = TypeKeyRegistry.resolve("UserDto");
 * }
 *
 * // With type assertion
 * Class<UserDto> userType = TypeKeyRegistry.resolve("UserDto", UserDto.class);
 *
 * // Reverse lookup from class to key
 * String key = TypeKeyRegistry.keyOf(UserDto.class);
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <p>
 * The provider and reverse map are initialized lazily on first access using double-checked locking.
 * The reverse map enables fast class → key lookup (for serialization and persistence).
 * </p>
 *
 * <h2>Resolution Strategy</h2>
 * <p>
 * Resolving a key to a {@link Class} proceeds in tiers:
 * </p>
 * <ol>
 *   <li><b>Generated registry</b>: keys of {@code @TypeKey}-annotated application types.</li>
 *   <li><b>Java primitives</b>: string names of primitive types, e.g. "int" → int.class.</li>
 *   <li><b>Classpath class</b>: best‑effort {@code Class.forName(key)} for FQCNs.</li>
 * </ol>
 *
 * <h2>Reverse Lookup Strategy</h2>
 * <p>
 * Converting a {@link Class} to a key proceeds as:
 * </p>
 * <ol>
 *   <li><b>Generated reverse registry</b>: {@code @TypeKey}-annotated types.</li>
 *   <li><b>Java primitives</b>: primitive class → its language name (e.g., boolean.class → "boolean").</li>
 *   <li><b>Arrays</b>: resolve component key and append {@code []}.</li>
 *   <li><b>Fallback</b>: fully-qualified class name.</li>
 * </ol>
 *
 * <p>
 * For application classes, prefer {@code @TypeKey} to avoid leaking implementation names into
 * persisted keys. Without {@code @TypeKey}, reverse lookup falls back to the FQCN.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
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
     * Checks whether the generated registry contains a class for the given logical key.
     * <p>
     * This method only checks the generated {@code @TypeKey} registry and does
     * <em>not</em> consider fallback mechanisms (primitives, Java standard library).
     * Use {@link #resolve(String)} to attempt full resolution with fallbacks.
     * </p>
     *
     * @param key Logical type identifier; must not be {@code null}.
     * @return {@code true} if the generated registry contains a mapping for this key;
     *         {@code false} otherwise.
     * @throws NullPointerException If {@code key} is {@code null}.
     */
    public static boolean canResolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return getRegistryProvider().getRegistry().containsKey(key);
    }

    /**
     * Resolves a type by its logical key using a multi-tiered fallback strategy.
     * <p>
     * Resolution proceeds as follows:
     * </p>
     * <ol>
     *   <li><b>Generated registry</b>: Looks up {@code @TypeKey}-annotated types.</li>
     *   <li><b>Java primitives</b>: Maps {@code "boolean"} → {@code boolean.class}, etc.</li>
     *   <li><b>Classpath class</b>: Loads {@code "java.util.List"} → {@code List.class}.</li>
     * </ol>
     *
     * @param key Logical type identifier; must not be {@code null}.
     * @return The resolved {@link Class} for the given key.
     * @throws NullPointerException  If {@code key} is {@code null}.
     * @throws IllegalStateException If no mapping exists for the given key in any tier.
     */
    public static Class<?> resolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");

        // 1. Registry
        Class<?> type = getRegistryProvider().getRegistry().get(key);
        if (type != null) return type;

        // 2. Primitives
        switch (key) {
            case "boolean": return boolean.class;
            case "byte":    return byte.class;
            case "short":   return short.class;
            case "int":     return int.class;
            case "long":    return long.class;
            case "float":   return float.class;
            case "double":  return double.class;
            case "char":    return char.class;
        }

        // 3. Generic fallback: try to load class anywhere on the classpath
        try {
            return Class.forName(key);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Type not found: " + key, e);
        }
    }

    /**
     * Resolves the type by key and verifies that it matches the expected class.
     * <p>
     * This overload provides an additional safety check: it ensures the resolved type
     * (including fallbacks) is exactly {@code targetType}. If the mapping does not match,
     * an {@link IllegalArgumentException} is thrown.
     * </p>
     *
     * @param key        Logical type identifier; must not be {@code null}.
     * @param targetType Expected concrete class; must not be {@code null}.
     * @param <T>        The expected type parameter.
     * @return The {@code targetType} if the mapping is valid.
     * @throws NullPointerException     If {@code key} or {@code targetType} is {@code null}.
     * @throws IllegalArgumentException If the resolved type does not match {@code targetType}.
     */
    public static <T> Class<T> resolve(String key, Class<T> targetType) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(targetType, "targetType cannot be null");

        Class<?> mapped = resolve(key);

        if (!targetType.equals(mapped)) {
            throw new IllegalArgumentException(
                    "Registry mismatch for key '" + key + "'. Expected: " +
                            targetType.getName() + ", found: " + mapped.getName()
            );
        }

        //noinspection unchecked
        return (Class<T>) mapped;
    }

    /**
     * Returns the logical {@link TypeKey} value associated with the given class.
     * <p>
     * This method performs reverse lookup using a multi-tiered strategy:
     * </p>
     * <ol>
     *   <li><b>Generated reverse registry</b>: {@code @TypeKey}-annotated types.</li>
     *   <li><b>Java primitives</b>: {@code boolean.class → "boolean"}.</li>
     *   <li><b>Arrays</b>: Recursively resolve component + {@code "[]"} suffix.</li>
     *   <li><b>Fallback</b>: fully-qualified class name.</li>
     * </ol>
     *
     * @param type Class to lookup; must not be {@code null}.
     * @return The stable logical key for this class.
     * @throws NullPointerException  If {@code type} is {@code null}.
     * @throws IllegalStateException If no key can be determined for the class.
     */
    public static String keyOf(Class<?> type) {
        Objects.requireNonNull(type, "type cannot be null");
        getRegistryProvider(); // Ensure provider and reverse registry are initialized.

        // Registry lookup
        String key = REVERTED_REGISTRY.get(type);
        if (key != null) return key;

        // Primitives
        if (type.isPrimitive()) return type.getName();

        // Arrays
        if (type.isArray()) {
            return keyOf(type.getComponentType()) + "[]";
        }

        // Generic fallback: fully qualified name
        return type.getName();
    }
}
