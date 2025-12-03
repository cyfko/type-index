package io.github.cyfko.typeindex;

import io.github.cyfko.typeindex.providers.RegistryProvider;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Global static registry for resolving Java types referenced by a stable logical key.
 * <p>
 * This registry is populated automatically by the annotation processor
 * {@code TypeIndexProcessor}, which generates a {@code RegistryProviderImpl}
 * containing a statically initialized map of keys to fully-qualified class names.
 * <p>
 * A typical use-case is to store a stable key in a database instead of a class
 * name, thus avoiding breakage if classes are renamed or moved. The annotation
 * {@link TypeKey} ensures each class explicitly declares its stable identifier.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (TypeRegistry.canResolve("UserDto")) {
 *     Class<?> type = TypeRegistry.resolve("UserDto");
 * }
 * }</pre>
 *
 * <p>
 * The registry is lazily loaded using double-checked locking to minimize overhead
 * and ensure thread safety.
 */
public final class TypeRegistry {
    private static final Logger log = Logger.getLogger(TypeRegistry.class.getName());

    /**
     * Singleton provider instance generated at compile-time.
     */
    private static volatile RegistryProvider PROVIDER;

    private TypeRegistry() {
        // Not instantiable
    }

    /**
     * Returns the generated {@link RegistryProvider}.
     *
     * @return the metadata provider
     * @throws IllegalStateException if the generated class cannot be loaded
     */
    public static RegistryProvider getRegistryProvider() {
        if (PROVIDER == null) {
            synchronized (TypeRegistry.class) {
                if (PROVIDER == null) {
                    PROVIDER = loadProvider();
                }
            }
        }
        return PROVIDER;
    }

    /**
     * Loads the auto-generated {@code RegistryProviderImpl} using reflection.
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
                system is configured for annotation processing. \n
                """ + e);
            return Map::of;
        } catch (InvocationTargetException | InstantiationException |
                 NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to instantiate RegistryProviderImpl", e);
        }
    }

    /**
     * Returns {@code true} if the registry contains a class for the given key.
     */
    public static boolean canResolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return getRegistryProvider().getRegistry().containsKey(key);
    }

    /**
     * Resolves a type by key.
     *
     * @param key logical type identifier
     * @return the class associated with the key
     * @throws IllegalStateException if the key is unknown
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
     * Resolves the type and checks that the expected class matches.
     *
     * @param key        logical type key
     * @param targetType expected concrete class
     */
    public static <T> Class<T> resolve(String key, Class<T> targetType) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(targetType);

        Class<?> mapped = getRegistryProvider().getRegistry().get(key);

        if (!targetType.equals(mapped)) {
            throw new IllegalArgumentException(
                    "Registry mismatch for key '" + key + "'. Expected: "
                            + targetType.getName() + ", found: " + mapped
            );
        }

        return targetType;
    }
}
