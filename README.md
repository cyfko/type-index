# TypeIndex

A compile-time type registry for Java that maps stable logical keys to classes — with zero runtime overhead, full bidirectional lookup, and built-in support for clean architectures.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/type-index)](https://central.sonatype.com/artifact/io.github.cyfko/type-index)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

## Why TypeIndex?

Persisting or exchanging type references using fully qualified class names (`com.example.UserDto`) is fragile: a package move or class rename silently breaks all stored data.

TypeIndex decouples the **stable business key** from the **Java class name**. The registry is generated at compile time — no reflection scanning, no runtime cost.

```java
// Store "user-profile" in your DB instead of "com.example.domain.UserProfile"
String key = TypeKeyRegistry.keyOf(UserProfile.class);   // → "user-profile"
Class<?> type = TypeKeyRegistry.resolve("user-profile"); // → UserProfile.class
```

---

## Features

| | |
|---|---|
| **Compile-time registry** | Generated as a static immutable `Map` — zero runtime overhead |
| **Two registration modes** | Direct `@TypeKey` on the class, or external `@TypeKeyConfig` on an interface |
| **Non-intrusive for clean architectures** | Keep domain classes free of infrastructure annotations (Hexagonal, Clean, DDD) |
| **Bidirectional lookup** | `resolve(key)` and `keyOf(class)` — both O(1) |
| **Multi-tier resolution** | Falls back to primitives, arrays, and classpath FQCNs |
| **Compile-time validation** | Duplicate keys, type conflicts, invalid characters — all caught at build time |
| **Deferred execution support** | `wrap()` / `unwrap()` preserve parameter types across serialization boundaries |
| **Thread-safe** | Lazy initialization with double-checked locking |
| **Record & Enum support** | Works with classes, records, and enums |

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>type-index</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```groovy
dependencies {
    implementation 'io.github.cyfko:type-index:1.1.0'
    annotationProcessor 'io.github.cyfko:type-index:1.1.0'
}
```

---

## Registration

TypeIndex offers two complementary registration modes. Both produce identical runtime behaviour — `resolve()` and `keyOf()` work the same regardless of how the type was registered.

### Option A — Direct Registration

Annotate your class, record, or enum directly. Best suited for standard applications where you own the source types.

```java
@TypeKey("user-profile")
public class UserProfile { }

@TypeKey("order-v2")
public record Order(String orderId, BigDecimal total) { }

@TypeKey("order-status")
public enum OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED }
```

### Option B — External Registration (Non-Intrusive)

Declare mappings on a **configuration interface** annotated with `@TypeKeyConfig`. The interface is never instantiated — it is a compile-time declaration only.

This mode is designed for **Hexagonal Architecture**, **Clean Architecture**, and **DDD**, where domain or core classes must remain free of infrastructure annotations.

```java
@TypeKeyConfig
public interface AppTypeKeys {

    @TypeKey("user-profile")
    UserProfile userProfile();

    @TypeKey("order-v2")
    Order order();

    @TypeKey("order-status")
    OrderStatus orderStatus();
}
```

Constraints enforced at compile time:
- `@TypeKeyConfig` can only be applied to **interfaces**.
- Each method must have **zero parameters**.
- The return type must be a **class, record, or enum** — no `void`, no primitives, no parameterized generics (`List<String>` is rejected).
- Methods without `@TypeKey` are silently ignored.

Both modes can coexist freely in the same project.

---

## Lookup API

### Forward Resolution — key → class

```java
// Check existence (registry only, no fallbacks)
boolean exists = TypeKeyRegistry.canResolve("user-profile");

// Resolve (multi-tier, see below)
Class<?> type = TypeKeyRegistry.resolve("user-profile");

// Resolve with type assertion
Class<UserProfile> type = TypeKeyRegistry.resolve("user-profile", UserProfile.class);
```

### Reverse Resolution — class → key

```java
String key = TypeKeyRegistry.keyOf(UserProfile.class); // → "user-profile"
String key = TypeKeyRegistry.keyOf(int.class);         // → "int"
String key = TypeKeyRegistry.keyOf(int[].class);       // → "int[]"
String key = TypeKeyRegistry.keyOf(ArrayList.class);   // → "java.util.ArrayList" (FQCN fallback)
```

### Multi-Tier Resolution Strategy

**Forward** (`resolve(key)`):

| Tier | What it checks |
|------|----------------|
| 1 | Generated registry — `@TypeKey` / `@TypeKeyConfig` types |
| 2 | Array types — key ending with `[]`, resolved recursively |
| 3 | Java primitives — `"int"`, `"boolean"`, `"none"` (→ `void.class`), etc. |
| 4 | Classpath fallback — `Class.forName(key)` |

**Reverse** (`keyOf(class)`):

| Tier | What it checks |
|------|----------------|
| 1 | Generated reverse registry |
| 2 | Java primitives |
| 3 | Array types — component key + `"[]"` suffix |
| 4 | Fully qualified class name |

---

## Parameter Envelopes — Deferred Execution

`TypeKeyRegistry` includes a utility for capturing method arguments with their type information. This is useful for **command buses**, **RPC**, **remote dispatch**, and any pattern where parameters must be serialized and reconstructed later.

### Wrapping

```java
Object[] args = { user, "hello", 42 };
List<ParamEnvelope> envelopes = TypeKeyRegistry.wrap(args);
// Each envelope holds: typeKey ("user-profile", "java.lang.String", "int") + raw value
```

`null` arguments are represented with the special key `"null"` and reconstructed as `null`.

### Unwrapping

```java
Object[] restored = TypeKeyRegistry.unwrap(envelopes, (value, type) ->
    objectMapper.convertValue(value, type)
);
```

The mapper function is caller-supplied — TypeIndex handles type resolution, you handle deserialization. Compatible with any serialization library (Jackson, Gson, custom).

---

## Use Cases

### Database Entity Mapping

Store stable keys instead of class names in your schema:

```java
String key = TypeKeyRegistry.keyOf(event.getClass()); // "event.order-created"
// persist key + payload

// On read:
Class<?> type = TypeKeyRegistry.resolve(storedKey);
DomainEvent event = objectMapper.readValue(payload, type);
```

### Polymorphic JSON Serialization

```java
// Serialize
gen.writeStringField("@type", TypeKeyRegistry.keyOf(value.getClass()));

// Deserialize
String typeKey = node.get("@type").asText();
Class<?> targetClass = TypeKeyRegistry.resolve(typeKey);
return ctx.readValue(node.get("data").traverse(codec), targetClass);
```

### Event Sourcing

```java
@TypeKey("event.order-created")
public record OrderCreatedEvent(String orderId, LocalDateTime timestamp) implements DomainEvent { }

@TypeKey("event.order-shipped")
public record OrderShippedEvent(String orderId, String trackingNumber) implements DomainEvent { }

// Reconstruct from event store
Class<? extends DomainEvent> eventClass =
    (Class<? extends DomainEvent>) TypeKeyRegistry.resolve(storedType);
DomainEvent event = objectMapper.readValue(data, eventClass);
```

### Plugin System

```java
@TypeKey("notifier.email")
public class EmailNotifier implements Notifier { }

@TypeKey("notifier.sms")
public class SmsNotifier implements Notifier { }

// Load from configuration
String pluginKey = config.getString("notifier.plugin");
Notifier notifier = (Notifier) TypeKeyRegistry.resolve(pluginKey)
    .getDeclaredConstructor().newInstance();
```

### Versioned APIs

```java
@TypeKey("api.user.v1")
public record UserDtoV1(String name) { }

@TypeKey("api.user.v2")
public record UserDtoV2(String firstName, String lastName) { }

// Client-driven version selection
Class<?> dto = TypeKeyRegistry.resolve("api.user." + request.getHeader("API-Version"));
```

### Message Queue Processing

```java
@TypeKey("command.create-user")
public record CreateUserCommand(String username, String email) { }

public void processMessage(Message message) {
    Class<?> commandClass = TypeKeyRegistry.resolve(message.getHeader("command-type"));
    Object command = objectMapper.readValue(message.getBody(), commandClass);
    commandBus.dispatch(command);
}
```

---

## Key Naming Rules

Keys must match the pattern `^[a-zA-Z0-9.\-#_]+$`.

| Character | Allowed |
|-----------|---------|
| Alphanumeric `a-z A-Z 0-9` | ✅ |
| Dot `.` | ✅ |
| Hyphen `-` | ✅ |
| Hash `#` | ✅ |
| Underscore `_` | ✅ |
| Space, `/`, `@`, and others | ❌ |

**Recommended conventions:**

```java
@TypeKey("domain.user")              // Namespaced
@TypeKey("event.order-created")      // Functional grouping
@TypeKey("api.user.v2")              // Versioned
@TypeKey("config#production")        // Environment variant
```

---

## Compile-Time Validation

The annotation processor enforces all constraints at build time. These are hard compilation errors, not warnings.

| Violation | Error message |
|-----------|---------------|
| Duplicate key (two different classes) | `Duplicate @TypeKey value 'x' found on ...` |
| Duplicate type (same class, two keys) | `Type '...' is registered with conflicting keys` |
| Direct + config conflict | `Type '...' is registered both directly and via @TypeKeyConfig` |
| Invalid key characters | `@TypeKey value 'x' contains invalid characters` |
| Blank key | `@TypeKey value cannot be blank` |
| `@TypeKey` on an interface | `@TypeKey can only be applied to classes, records or enums` |
| `@TypeKeyConfig` on a non-interface | `@TypeKeyConfig can only be applied to interfaces` |
| Method with parameters in `@TypeKeyConfig` | `Methods in @TypeKeyConfig interfaces must have zero parameters` |
| `void` / primitive / generic return type | `Return type of method in @TypeKeyConfig must be a class, record or enum` |

---

## Runtime Exceptions

```java
TypeKeyRegistry.resolve("unknown");
// → IllegalStateException: "Type not found: unknown"

TypeKeyRegistry.resolve("user-profile", Order.class);
// → IllegalArgumentException: "Registry mismatch for key 'user-profile'. Expected: Order, found: UserProfile"

TypeKeyRegistry.resolve(null);
// → NullPointerException: "key cannot be null"

TypeKeyRegistry.keyOf(null);
// → NullPointerException: "type cannot be null"
```

---

## Architecture

```
Compile time
────────────────────────────────────────────────────
  @TypeKey("user")          @TypeKeyConfig
  public class User { }     public interface Keys {
                                @TypeKey("order")
                                Order order();
                            }
            │                         │
            └──────────┬──────────────┘
                       ▼
            TypeIndexProcessor (APT)
            · Validates keys & types
            · Detects conflicts
            · Generates source file
                       │
                       ▼
            RegistryProviderImpl (generated)
            Map.ofEntries(
                Map.entry("user",  User.class),
                Map.entry("order", Order.class)
            )

Runtime
────────────────────────────────────────────────────
            TypeKeyRegistry
            · Loads RegistryProviderImpl once via reflection
            · Builds reverse map (class → key) on first access
            · Thread-safe: volatile + double-checked locking
            · Exposes resolve(), keyOf(), canResolve(), wrap(), unwrap()
```

### Generated code example

For `@TypeKey("user") class User` and `@TypeKey("order") record Order`:

```java
@Generated("io.github.cyfko.typeindex.processor.TypeIndexProcessor")
public final class RegistryProviderImpl implements RegistryProvider {

    private static final Map<String, Class<?>> REGISTRY = Map.ofEntries(
        Map.entry("user",  com.example.User.class),
        Map.entry("order", com.example.Order.class)
    );

    @Override
    public Map<String, Class<?>> getRegistry() {
        return REGISTRY;
    }
}
```

---

## Performance

| Operation | Throughput |
|-----------|-----------|
| `resolve(key)` | ~8,200 ops/ms |
| `canResolve(key)` | ~9,100 ops/ms |
| `keyOf(class)` | ~8,500 ops/ms |
| First access (init) | ~2 ops/ms (one-time) |
| `resolve` primitive | ~9,800 ops/ms |
| `resolve` classpath fallback | ~1,200 ops/ms |

Memory: ~48 bytes per entry (forward map + reverse map + class reference).

---

## Troubleshooting

**`IllegalStateException: Cannot load the generated registry`**
- Annotation processing is not enabled in your build/IDE
- `type-index` is missing from the annotation processor path
- Clean and rebuild the project

**`IllegalStateException: Type not found: xxx`**
- The class was not annotated or declared in a `@TypeKeyConfig`
- Typo in the key — use `canResolve()` to guard before resolving
- Rebuild to regenerate the registry

**`keyOf()` returns a FQCN instead of the expected key**
- The class is not registered — add `@TypeKey` or a `@TypeKeyConfig` entry
- Rebuild to regenerate the reverse registry

**`WARNING: No @TypeKey or @TypeKeyConfig annotations found. Registry will be empty.`**
- No annotated types found in the compilation unit — this is just a warning, the build succeeds

---

## Best Practices

1. **Treat keys as public API** — once stored in production data, a key must never change.
2. **Namespace your keys** — use dots to group by domain: `event.order-created`, `dto.user-request`.
3. **Version evolving types** — `api.user.v1`, `api.user.v2` rather than mutating a single key.
4. **Prefer `@TypeKey` over FQCN fallback** — classpath fallback breaks on refactoring; explicit keys don't.
5. **Use `@TypeKeyConfig` in clean architectures** — keep domain classes annotation-free.
6. **Write key existence tests** — guard critical keys with `assertTrue(TypeKeyRegistry.canResolve("my-key"))`.

---

## Contributing

```bash
git clone https://github.com/cyfko/type-index.git
cd type-index
./mvnw clean install        # build + test
./mvnw test                 # tests only
./mvnw test -Dtest=TypeIndexProcessorTest  # processor tests
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

**Made with ❤️ by [Frank KOSSI](https://github.com/cyfko) — [Kunrin SA](https://www.kunrin.com)**
