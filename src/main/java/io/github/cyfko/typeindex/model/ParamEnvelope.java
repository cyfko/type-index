package io.github.cyfko.typeindex.model;

/**
 * Immutable envelope that preserves the declared Java type of a parameter
 * along with its raw, pre-serialization value.
 *
 * <p>
 * This record is used when capturing method invocation arguments so that the
 * concrete runtime type of each parameter can be preserved independently of
 * the serialization mechanism used later.
 * </p>
 *
 * <p>
 * The {@code typeKey} stores the logical representation of the parameter's
 * Java type (for example: {@code "java.lang.String"}, {@code "com.app.MachinDTO[]"},
 * or {@code "int"}). A corresponding {@link io.github.cyfko.typeindex.TypeKeyRegistry#resolve(String)} call
 * is used to convert this key back into a {@link Class} object.
 * </p>
 *
 * <p>
 * The {@code value} field stores the raw value directly and is expected to be
 * processed later by a user-provided mapper function capable of converting it
 * into the appropriate Java type during deserialization.
 * </p>
 *
 * @param typeKey Logical identifier for the Java type of the parameter; never {@code null}
 *                for non-null values. The special key {@code "null"} is used to represent
 *                {@code null} parameters.
 * @param value   Raw value associated with the parameter; may be {@code null}.
 */
public record ParamEnvelope(
        String typeKey,
        Object value
) {}
