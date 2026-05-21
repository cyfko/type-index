# Changelog

All notable changes to the TypeIndex project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.0] - 2026-05-21

### Added
- **Non-intrusive External Registration**: Added `@TypeKeyConfig` annotation to define type-key mappings externally on interface declarations, allowing clean core domains to remain free of external dependencies (highly beneficial for Hexagonal/Clean architectures and DDD).
- **Compile-Time Validation Rules**:
  - Validated that `@TypeKeyConfig` is only applied to interfaces.
  - Validated that methods within `@TypeKeyConfig` interfaces have zero parameters.
  - Validated that `@TypeKeyConfig` methods return concrete classes, records, or enums (excluding primitive types, `void`, arrays, and parameterized generic types like `List<String>`).
  - Added global uniqueness checks mapping conflict prevention (duplicate keys, duplicate type mappings, and coexistence conflicts between direct `@TypeKey` annotations and external config interface mappings).
- **Target Support for Methods**: Allowed `@TypeKey` to target `ElementType.METHOD` in addition to `ElementType.TYPE` to support mappings in `@TypeKeyConfig` configuration interfaces.

### Changed
- **Annotation Targeting**: Removed `ElementType.FIELD` from the `@Target` annotation of `@TypeKey`. Since the retention policy is `RetentionPolicy.SOURCE`, using this annotation on fields at runtime was a design flaw that resulted in silent reflective lookup failures.
- **Documentation**: Overhauled `README.md` and `TypeKeyRegistry.java` class Javadoc to explain non-intrusive registration, showcase Hexagonal Architecture decoupling benefits, and remove outdated field annotation references.

### Fixed
- **Warnings and Lints**:
  - Added `@SuppressWarnings("unchecked")` in `TypeKeyRegistry.java` to eliminate compiler unchecked type-cast warnings during type resolution.
  - Removed a duplicate configuration block for the `central-publishing-maven-plugin` in `pom.xml` to eliminate build warnings and ensure clean plugin execution.

---

## [1.0.0] - 2025-03-10

### Added
- Initial release of TypeIndex.
- Support for direct `@TypeKey` annotations on classes, records, and enums.
- Compile-time annotation processing to generate a static, immutable type-key registry.
- Global `TypeKeyRegistry` class with multi-tiered resolution strategies (Registry, Primitives, Arrays, Classpath lookup) and reverse lookup capability.
- Param envelope utility (`ParamEnvelope`, `wrap`, `unwrap`) to simplify deferred execution parameter mapping.
