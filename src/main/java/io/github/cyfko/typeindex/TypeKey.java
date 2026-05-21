package io.github.cyfko.typeindex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a stable, logical business identifier for a Java type or field.
 *
 * <p>
 * {@code @TypeKey} is used in conjunction with the TypeIndex annotation processor
 * to generate a compile-time registry that maps stable keys to concrete Java
 * classes. Instead of persisting or exchanging fully qualified class names,
 * applications can rely on these stable keys, making refactorings (renames,
 * package moves) safe for persisted data and external integrations.
 * </p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>The {@linkplain #value() key} is a <strong>business identifier</strong>,
 *       not a technical Java name.</li>
 *   <li>Keys must be <strong>unique</strong> across all annotated types within
 *       the compilation unit processed by the annotation processor.</li>
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
 *   <li>{@code @TypeKey} can only be applied to classes, records, enums, or fields.</li>
 *   <li>The key value must not be {@code null} or blank.</li>
 *   <li>The key may contain only:
 *       alphanumeric characters and {@code '.'}, {@code '-'}, {@code '#'}, {@code '_' }.</li>
 *   <li>Keys must be globally unique; any duplicate key will cause compilation to fail,
 *       with diagnostics pointing to both conflicting declarations.</li>
 * </ul>
 *
 * <h2>Retention and Processing</h2>
 * <ul>
 *   <li>The retention policy is {@link RetentionPolicy#SOURCE}, which means the
 *       annotation is available only during compilation.</li>
 *   <li>The runtime registry is populated exclusively through generated code
 *       (e.g. {@code RegistryProviderImpl}); {@code @TypeKey} is not visible
 *       via reflection at runtime.</li>
 * </ul>
 *
 * <h2>Usage on Types</h2>
 * <p>
 * When applied to a class, record, or enum, {@code @TypeKey} registers the type
 * in the generated registry, enabling lookup by its stable key.
 * </p>
 *
 * <h3>Basic Type Registration</h3>
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
 * <h3>Resolving Types at Runtime</h3>
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
 * <h2>Usage on Fields</h2>
 * <p>
 * When applied to fields, {@code @TypeKey} serves as metadata to identify
 * and document the role of specific dependencies, strategies, or components
 * within a class. This enables:
 * </p>
 * <ul>
 *   <li><strong>Type discrimination</strong> for polymorphic fields</li>
 *   <li><strong>Strategy pattern</strong> implementations with runtime selection</li>
 *   <li><strong>Plugin system</strong> integration and discovery</li>
 *   <li><strong>Dependency documentation</strong> with stable identifiers</li>
 *   <li><strong>Configuration management</strong> with type-safe keys</li>
 * </ul>
 *
 * <h3>Strategy Pattern Example</h3>
 * <pre>{@code
 * public class PaymentProcessor {
 *     @TypeKey("payment.strategy.credit-card")
 *     private CreditCardStrategy creditCardStrategy;
 *
 *     @TypeKey("payment.strategy.paypal")
 *     private PayPalStrategy paypalStrategy;
 *
 *     @TypeKey("payment.strategy.crypto")
 *     private CryptoStrategy cryptoStrategy;
 *
 *     public void process(String strategyKey, Payment payment) {
 *         // Dynamic strategy selection based on key
 *         PaymentStrategy strategy = selectStrategyByKey(strategyKey);
 *         strategy.process(payment);
 *     }
 *
 *     private PaymentStrategy selectStrategyByKey(String key) {
 *         return switch (key) {
 *             case "payment.strategy.credit-card" -> creditCardStrategy;
 *             case "payment.strategy.paypal" -> paypalStrategy;
 *             case "payment.strategy.crypto" -> cryptoStrategy;
 *             default -> throw new IllegalArgumentException("Unknown strategy: " + key);
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h3>Plugin System Example</h3>
 * <pre>{@code
 * public class PluginManager {
 *     @TypeKey("plugin.auth.oauth2")
 *     private OAuth2Plugin oauth2Plugin;
 *
 *     @TypeKey("plugin.auth.saml")
 *     private SamlPlugin samlPlugin;
 *
 *     @TypeKey("plugin.storage.s3")
 *     private S3StoragePlugin s3Plugin;
 *
 *     // Auto-discover and register plugins
 *     public void initialize() throws IllegalAccessException {
 *         for (Field field : this.getClass().getDeclaredFields()) {
 *             TypeKey annotation = field.getAnnotation(TypeKey.class);
 *             if (annotation != null) {
 *                 field.setAccessible(true);
 *                 Object plugin = field.get(this);
 *                 registerPlugin(annotation.value(), plugin);
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Event Handler Registry Example</h3>
 * <pre>{@code
 * public class EventBus {
 *     @TypeKey("handler.order-created")
 *     private OrderCreatedHandler orderCreatedHandler;
 *
 *     @TypeKey("handler.payment-completed")
 *     private PaymentCompletedHandler paymentCompletedHandler;
 *
 *     @TypeKey("handler.user-registered")
 *     private UserRegisteredHandler userRegisteredHandler;
 *
 *     public void dispatch(String eventType, Event event) {
 *         EventHandler handler = findHandlerByKey(eventType);
 *         if (handler != null) {
 *             handler.handle(event);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Configuration Management Example</h3>
 * <pre>{@code
 * @Configuration
 * public class DataSourceConfig {
 *     @TypeKey("datasource.primary")
 *     private DataSource primaryDataSource;
 *
 *     @TypeKey("datasource.analytics")
 *     private DataSource analyticsDataSource;
 *
 *     @TypeKey("datasource.reporting")
 *     private DataSource reportingDataSource;
 *
 *     public DataSource getDataSource(String key) {
 *         return switch (key) {
 *             case "datasource.primary" -> primaryDataSource;
 *             case "datasource.analytics" -> analyticsDataSource;
 *             case "datasource.reporting" -> reportingDataSource;
 *             default -> primaryDataSource; // fallback
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h3>Validator Registry Example</h3>
 * <pre>{@code
 * public class ValidationEngine {
 *     @TypeKey("validator.email")
 *     private EmailValidator emailValidator;
 *
 *     @TypeKey("validator.phone")
 *     private PhoneValidator phoneValidator;
 *
 *     @TypeKey("validator.credit-card")
 *     private CreditCardValidator creditCardValidator;
 *
 *     public ValidationResult validate(String validatorKey, Object value) {
 *         Validator validator = getValidatorByKey(validatorKey);
 *         return validator.validate(value);
 *     }
 * }
 * }</pre>
 *
 * <h2>Field-Level Benefits</h2>
 * <p>
 * Using {@code @TypeKey} on fields provides several advantages:
 * </p>
 * <ul>
 *   <li><strong>Self-documenting code</strong>: Keys clearly identify the role
 *       of each dependency</li>
 *   <li><strong>Runtime discovery</strong>: Reflection can be used to find all
 *       annotated fields for auto-registration</li>
 *   <li><strong>Configuration-driven selection</strong>: External configuration
 *       can specify which component to use by key</li>
 *   <li><strong>Testing flexibility</strong>: Easy to mock or swap implementations
 *       identified by keys</li>
 *   <li><strong>Type safety</strong>: Unlike string-based registries, field types
 *       are checked at compile time</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 *
 * <h3>For Types</h3>
 * <ul>
 *   <li>Use descriptive, hierarchical keys: {@code "domain.user"}, {@code "event.order-created"}</li>
 *   <li>Include version information for evolving schemas: {@code "api.user.v2"}</li>
 *   <li>Never change a key once it's used in production</li>
 *   <li>Document the key in the class JavaDoc for discoverability</li>
 * </ul>
 *
 * <h3>For Fields</h3>
 * <ul>
 *   <li>Use consistent naming patterns within the same class:
 *       {@code "strategy.*"}, {@code "handler.*"}, {@code "plugin.*"}</li>
 *   <li>Align field keys with their corresponding type keys when applicable</li>
 *   <li>Document the selection logic if keys are used for runtime dispatch</li>
 *   <li>Consider using enums or constants to avoid hardcoded key strings</li>
 * </ul>
 *
 * <h2>Integration Patterns</h2>
 *
 * <h3>With Dependency Injection (Spring)</h3>
 * <pre>{@code
 * @Component
 * public class ServiceRegistry {
 *     @Autowired
 *     @TypeKey("service.user-management")
 *     private UserService userService;
 *
 *     @Autowired
 *     @TypeKey("service.order-processing")
 *     private OrderService orderService;
 *
 *     public Service getService(String key) {
 *         // Use reflection to find field by TypeKey annotation
 *         return findServiceByKey(key);
 *     }
 * }
 * }</pre>
 *
 * <h3>With Factory Pattern</h3>
 * <pre>{@code
 * public class HandlerFactory {
 *     @TypeKey("handler.xml")
 *     private final XmlHandler xmlHandler = new XmlHandler();
 *
 *     @TypeKey("handler.json")
 *     private final JsonHandler jsonHandler = new JsonHandler();
 *
 *     @TypeKey("handler.csv")
 *     private final CsvHandler csvHandler = new CsvHandler();
 *
 *     public Handler createHandler(String format) {
 *         String key = "handler." + format.toLowerCase();
 *         return findHandlerByKey(key);
 *     }
 * }
 * }</pre>
 *
 * <h3>With Registry Pattern</h3>
 * <pre>{@code
 * public abstract class PluginRegistry {
 *     protected final Map<String, Object> plugins = new HashMap<>();
 *
 *     public PluginRegistry() {
 *         registerPluginsFromFields();
 *     }
 *
 *     private void registerPluginsFromFields() {
 *         for (Field field : this.getClass().getDeclaredFields()) {
 *             TypeKey annotation = field.getAnnotation(TypeKey.class);
 *             if (annotation != null) {
 *                 field.setAccessible(true);
 *                 try {
 *                     plugins.put(annotation.value(), field.get(this));
 *                 } catch (IllegalAccessException e) {
 *                     throw new RuntimeException("Failed to register plugin: " +
 *                         annotation.value(), e);
 *                 }
 *             }
 *         }
 *     }
 *
 *     public <T> T getPlugin(String key, Class<T> type) {
 *         return type.cast(plugins.get(key));
 *     }
 * }
 * }</pre>
 *
 * <h2>Limitations and Considerations</h2>
 * <ul>
 *   <li><strong>Reflection overhead</strong>: Using reflection to discover annotated
 *       fields has runtime cost; consider caching results</li>
 *   <li><strong>No compile-time enforcement</strong>: Field annotations are not
 *       validated by the processor; only type annotations are validated</li>
 *   <li><strong>Manual registration</strong>: Unlike types, fields require manual
 *       discovery and registration logic</li>
 *   <li><strong>Visibility</strong>: Reflection-based access may require making
 *       fields accessible, which can break encapsulation</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p>
 * If you have existing string-based registries or maps, you can gradually
 * migrate to {@code @TypeKey} annotations:
 * </p>
 * <pre>{@code
 * // Before: Manual map-based registry
 * private static final Map<String, Validator> validators = Map.of(
 *     "email", new EmailValidator(),
 *     "phone", new PhoneValidator()
 * );
 *
 * // After: Annotation-based with auto-discovery
 * @TypeKey("validator.email")
 * private EmailValidator emailValidator = new EmailValidator();
 *
 * @TypeKey("validator.phone")
 * private PhoneValidator phoneValidator = new PhoneValidator();
 * }</pre>
 *
 * <p>
 * This pattern decouples persisted and external contracts from Java implementation
 * details while keeping type resolution centralized, explicit, and self-documenting.
 * </p>
 *
 * @see io.github.cyfko.typeindex.TypeKeyRegistry
 * @author Frank KOSSI
 * @since 1.0.0
 */
@Target({ElementType.TYPE, ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface TypeKey {

    /**
     * Stable business identifier associated with the annotated type or field.
     *
     * <p>
     * <strong>For types (classes, records, enums):</strong>
     * This value is registered in the generated type registry and can be used
     * to look up the class at runtime via {@link io.github.cyfko.typeindex.TypeKeyRegistry#resolve(String)}.
     * </p>
     *
     * <p>
     * <strong>For fields:</strong>
     * This value serves as metadata to identify and document the role of the
     * field within its containing class. It enables runtime discovery, dynamic
     * selection, and configuration-driven behavior.
     * </p>
     *
     * <p>
     * The key must:
     * </p>
     * <ul>
     *   <li>Not be {@code null} or blank</li>
     *   <li>Contain only alphanumeric characters and {@code '.'}, {@code '-'},
     *       {@code '#'}, {@code '_'}</li>
     *   <li>Be globally unique across all annotated types (enforced at compile time)</li>
     *   <li>Remain stable once used in production (never change)</li>
     * </ul>
     *
     * <h3>Naming Conventions</h3>
     * <ul>
     *   <li><strong>Types:</strong> Use hierarchical, descriptive names
     *       <br>Examples: {@code "domain.user"}, {@code "event.order-created"},
     *       {@code "api.v2.user-dto"}</li>
     *   <li><strong>Fields:</strong> Use prefixed patterns to group related components
     *       <br>Examples: {@code "strategy.payment.credit-card"},
     *       {@code "handler.order-created"}, {@code "plugin.auth.oauth2"}</li>
     * </ul>
     *
     * @return the stable logical key for this type or field
     */
    String value();
}
