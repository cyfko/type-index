package io.github.cyfko.typeindex;

import io.github.cyfko.typeindex.model.ParamEnvelope;
import io.github.cyfko.typeindex.providers.RegistryProvider;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Global static registry for resolving Java types by a stable logical key.
 *
 * <p>
 * Populated at build time by an annotation processor that generates a {@code RegistryProviderImpl}
 * containing a static map of keys to {@link Class} objects. At runtime, this class exposes that
 * registry and offers controlled fallbacks for primitives, arrays, and classpath types when
 * resolving logical keys.
 * </p>
 *
 * <h2>Why</h2>
 * <p>
 * Persist and exchange stable logical identifiers instead of fully-qualified class names to keep
 * stored data resilient to refactors (renames, package moves). Classes participating in the
 * application contract should declare {@link TypeKey}, which is validated and indexed at compile time.
 * When {@code @TypeKey} is not present, the fully-qualified class name is used as a fallback key.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (TypeKeyRegistry.canResolve("user-dto")) {
 *     Class<?> type = TypeKeyRegistry.resolve("user-dto");
 * }
 *
 * // With type assertion
 * Class<UserDto> userType = TypeKeyRegistry.resolve("user-dto", UserDto.class);
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
 *   <li><b>Arrays</b>: suffix {@code "[]"} resolved recursively via the component key.</li>
 *   <li><b>Java primitives</b>: string names of primitive types, e.g. {@code "int"} → {@code int.class}.</li>
 *   <li><b>Classpath class</b>: best‑effort {@link Class#forName(String)} for FQCNs.</li>
 * </ol>
 *
 * <h2>Reverse Lookup Strategy</h2>
 * <p>
 * Converting a {@link Class} to a key proceeds as:
 * </p>
 * <ol>
 *   <li><b>Generated reverse registry</b>: {@code @TypeKey}-annotated types.</li>
 *   <li><b>Java primitives</b>: primitive class → its language name (e.g., {@code boolean.class → "boolean"}).</li>
 *   <li><b>Arrays</b>: resolve component key and append {@code "[]"}.</li>
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

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "int", int.class,
            "long", long.class,
            "boolean", boolean.class,
            "double", double.class,
            "float", float.class,
            "short", short.class,
            "byte", byte.class,
            "char", char.class
    );

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
     * Checks whether a type can be resolved for the given logical key.
     *
     * <p>
     * This method delegates to {@link #resolve(String)} and returns {@code true} if resolution
     * succeeds without throwing an exception. It therefore considers all resolution tiers:
     * generated registry, arrays, primitives, and classpath lookups.
     * </p>
     *
     * @param key Logical type identifier; must not be {@code null}.
     * @return {@code true} if {@link #resolve(String)} succeeds, {@code false} otherwise.
     * @throws NullPointerException If {@code key} is {@code null}.
     */
    public static boolean canResolve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        try {
            resolve(key);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Resolves a type by its logical key using a multi-tiered fallback strategy.
     *
     * <p>Resolution proceeds as follows:</p>
     * <ol>
     *   <li><b>Generated registry</b>: Looks up {@code @TypeKey}-annotated types.</li>
     *   <li><b>Arrays</b>: Keys ending with {@code "[]"} are resolved recursively via the
     *       component key.</li>
     *   <li><b>Java primitives</b>: Maps {@code "boolean"} → {@code boolean.class}, etc.</li>
     *   <li><b>Classpath class</b>: Attempts {@link Class#forName(String)} on the remaining key.</li>
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

        // 2. Array handling
        if (key.endsWith("[]")) {
            String componentKey = key.substring(0, key.length() - 2);
            Class<?> componentClass = resolve(componentKey); // recursive
            return Array.newInstance(componentClass, 0).getClass();
        }

        // 3. Primitives
        Class<?> primitive = PRIMITIVES.get(key);
        if (primitive != null) return primitive;

        // 4. Generic fallback: try to load class anywhere on the classpath
        try {
            return Class.forName(key);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Type not found: " + key, e);
        }
    }

    /**
     * Resolves the type by key and verifies that it matches the expected class.
     *
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
     *
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
     * <p>
     * For application classes, annotating with {@link TypeKey} is strongly recommended so
     * that keys remain stable even if the class name or package changes.
     * </p>
     *
     * @param type Class to lookup; must not be {@code null}.
     * @return The stable logical key for this class.
     * @throws NullPointerException If {@code type} is {@code null}.
     */
    public static String keyOf(Class<?> type) {
        Objects.requireNonNull(type, "type cannot be null");
        getRegistryProvider(); // Ensure provider and reverse registry are initialized.
        return computeKey(type);
    }

    /** Compute key for a type, recursively handling arrays. */
    private static String computeKey(Class<?> type) {
        if (type.isArray()) {
            return computeKey(type.getComponentType()) + "[]";
        }

        // primitive or registry
        String key = REVERTED_REGISTRY.get(type);
        if (key != null) return key;

        for (Map.Entry<String, Class<?>> entry : PRIMITIVES.entrySet()) {
            if (entry.getValue().equals(type)) return entry.getKey();
        }

        // fallback
        return type.getName();
    }

    /**
     * Wraps an array of method parameters into {@link ParamEnvelope} instances,
     * preserving the runtime type of each argument.
     *
     * <p>
     * This method inspects each parameter, derives its logical type key via
     * {@link #keyOf(Class)}, and constructs a corresponding {@link ParamEnvelope}
     * that stores both the key and the raw value. Values are <strong>not</strong>
     * serialized here; the caller is free to choose any serialization mechanism later.
     * </p>
     *
     * <p>
     * {@code null} parameters are represented with a special {@code "null"} type key,
     * which is interpreted by {@link #unwrap(List, BiFunction)} to restore {@code null}
     * entries in the resulting array.
     * </p>
     *
     * <p>
     * This is typically used to capture method arguments for deferred execution, logging,
     * or remote dispatch while preserving precise type information.
     * </p>
     *
     * @param params Array of parameter values to wrap; may contain {@code null} elements.
     * @return A list of {@link ParamEnvelope} preserving parameter types and values,
     *         never {@code null}.
     */
    public static List<ParamEnvelope> wrap(Object[] params){
        List<ParamEnvelope> list = new ArrayList<>(params.length);

        for (Object param : params) {
            if (param == null) {
                list.add(new ParamEnvelope("null", null));
                continue;
            }

            String key = keyOf(param.getClass());
            list.add(new ParamEnvelope(key, param));
        }

        return list;
    }

    /**
     * Reconstructs an array of parameters from a list of {@link ParamEnvelope} instances,
     * using a user-provided mapper function to convert stored raw values into instances
     * of their corresponding Java types.
     *
     * <p>
     * For each envelope:
     * </p>
     * <ul>
     *   <li>If {@code typeKey} is {@code "null"}, the corresponding parameter is set to {@code null}.</li>
     *   <li>Otherwise, {@link #resolve(String)} is used to obtain the target {@link Class}, and the
     *       mapper is invoked as {@code mapper.apply(env.value(), resolvedType)}.</li>
     * </ul>
     *
     * <p>
     * This design decouples type resolution (handled by this registry) from actual deserialization
     * (controlled by the caller), and works with any serialization library (Jackson, Gson, custom).
     * </p>
     *
     * @param envelopes List of wrapped parameters produced by {@link #wrap(Object[])}; must not be {@code null}.
     * @param mapper    Function that converts a raw value and expected type into an actual typed instance;
     *                  must not be {@code null}.
     * @return A new array of parameters matching the original method arguments in size and order.
     * @throws IllegalStateException If a {@code typeKey} cannot be resolved.
     * @throws RuntimeException      If the mapper throws an exception during conversion.
     */
    public static Object[] unwrap(List<ParamEnvelope> envelopes, BiFunction<Object, Class<?>, Object> mapper) {
        Object[] params = new Object[envelopes.size()];

        for (int i = 0; i < envelopes.size(); i++) {
            ParamEnvelope env = envelopes.get(i);

            if ("null".equals(env.typeKey())) {
                params[i] = null;
                continue;
            }

            Class<?> type = resolve(env.typeKey());
            params[i] = mapper.apply(env.value(), type);
        }

        return params;
    }
}
