package io.github.cyfko.typeindex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an external type key configuration.
 *
 * <p>
 * Each method in this interface annotated with {@link TypeKey} declares
 * a mapping from a stable key to the method's return type, without
 * requiring the target type to carry any annotation.
 * </p>
 *
 * <p>
 * The interface is never instantiated — it serves purely as a
 * compile-time declaration for the annotation processor.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * @TypeKeyConfig
 * public interface AuthTypeKeys {
 *
 *     @TypeKey("auth.steps")
 *     AuthSteps steps();
 *
 *     @TypeKey("auth.signal.otp-sent")
 *     AuthSignal.OtpSent otpSent();
 *
 *     @TypeKey("auth.signal.otp-validated")
 *     AuthSignal.OtpValidated otpValidated();
 * }
 * }</pre>
 *
 * @see io.github.cyfko.typeindex.TypeKey
 * @see io.github.cyfko.typeindex.TypeKeyRegistry
 * @author Frank KOSSI
 * @since 1.1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface TypeKeyConfig {
}
