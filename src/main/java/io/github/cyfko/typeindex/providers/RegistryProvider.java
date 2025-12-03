package io.github.cyfko.typeindex.providers;

import java.util.Map;

/**
 * Provides access to the generated registry mapping keys to Java types.
 * <p>
 * This interface is implemented automatically by the annotation processor,
 * generating a deterministic and immutable registry.
 */
public interface RegistryProvider {

    /**
     * Returns an immutable map of type keys to Java classes.
     *
     * @return unmodifiable registry mapping keys to classes
     */
    Map<String, Class<?>> getRegistry();
}
