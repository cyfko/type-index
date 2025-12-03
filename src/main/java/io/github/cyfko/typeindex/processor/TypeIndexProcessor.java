package io.github.cyfko.typeindex.processor;

import com.google.auto.service.AutoService;
import io.github.cyfko.typeindex.TypeKey;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Annotation processor responsible for generating a type registry mapping
 * {@link TypeKey} identifiers to fully qualified class names.
 * <p>
 * The processor validates:
 * <ul>
 *     <li>that @TypeKey is used only on classes</li>
 *     <li>that keys contain only allowed characters: alphanumeric, '.', '-', '#', '_'</li>
 *     <li>that keys are globally unique</li>
 * </ul>
 * At the end of processing, a class named
 * {@code io.github.cyfko.typeindex.providers.RegistryProviderImpl}
 * is generated containing a static, immutable registry.
 * <p>
 * Compilation will fail if any validation errors are detected.
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("io.github.cyfko.typeindex.TypeKey")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class TypeIndexProcessor extends AbstractProcessor {

    private static final Pattern VALID_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z0-9.\\-#_]+$");

    private final Map<String, TypeElementInfo> entries = new LinkedHashMap<>();
    private boolean hasErrors = false;
    private boolean hasProcessedAnnotations = false;

    private static class TypeElementInfo {
        final String qualifiedName;
        final Element element;

        TypeElementInfo(String qualifiedName, Element element) {
            this.qualifiedName = qualifiedName;
            this.element = element;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (annotations.isEmpty()) {
            return false;
        }

        Messager log = processingEnv.getMessager();

        Set<? extends Element> annotatedElements = env.getElementsAnnotatedWith(TypeKey.class);

        if (!annotatedElements.isEmpty()) {
            hasProcessedAnnotations = true;
            log.printMessage(Diagnostic.Kind.NOTE, "Processing @TypeKey...");
        }

        for (Element element : annotatedElements) {

            if (element.getKind() != ElementKind.CLASS) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey can only be applied to classes", element);
                hasErrors = true;
                continue;
            }

            TypeElement type = (TypeElement) element;
            TypeKey annotation = type.getAnnotation(TypeKey.class);
            String key = annotation.value();

            // Validate key is not blank
            if (key == null || key.isBlank()) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey value cannot be blank", element);
                hasErrors = true;
                continue;
            }

            // Validate key contains only allowed characters
            if (!VALID_KEY_PATTERN.matcher(key).matches()) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey value '" + key + "' contains invalid characters. " +
                                "Only alphanumeric characters and '.', '-', '#', '_' are allowed",
                        element);
                hasErrors = true;
                continue;
            }

            // Check for duplicate keys
            if (entries.containsKey(key)) {
                TypeElementInfo existing = entries.get(key);
                String msg = "Duplicate @TypeKey value '" + key + "' found on "
                        + type.getQualifiedName() + ". Already used by "
                        + existing.qualifiedName;
                log.printMessage(Diagnostic.Kind.ERROR, msg, element);

                // Also report on the first occurrence for clarity
                log.printMessage(Diagnostic.Kind.ERROR,
                        "First usage of @TypeKey(\"" + key + "\")",
                        existing.element);

                hasErrors = true;
                continue;
            }

            entries.put(key, new TypeElementInfo(
                    type.getQualifiedName().toString(),
                    element
            ));
        }

        if (env.processingOver()) {
            if (hasErrors) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Cannot generate registry due to @TypeKey validation errors. " +
                                "Fix the errors above and recompile."
                );
            } else {
                if (!hasProcessedAnnotations) {
                    log.printMessage(Diagnostic.Kind.WARNING,
                            "No @TypeKey annotations found. Registry will be empty.");
                }
                writeProvider();
            }
        }

        return true;
    }

    private void writeProvider() {
        Messager log = processingEnv.getMessager();

        try {
            JavaFileObject file = processingEnv.getFiler()
                    .createSourceFile("io.github.cyfko.typeindex.providers.RegistryProviderImpl");

            try (Writer writer = file.openWriter()) {
                writeRegistryClass(writer);
            }

            log.printMessage(Diagnostic.Kind.NOTE,
                    "Generated RegistryProviderImpl with " + entries.size() + " entries");

        } catch (IOException e) {
            log.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate registry: " + e.getMessage());
        }
    }

    private void writeRegistryClass(Writer out) throws IOException {
        out.write("""
                package io.github.cyfko.typeindex.providers;

                import java.util.Map;
                import javax.annotation.processing.Generated;

                @Generated("io.github.cyfko.typeindex.processor.TypeIndexProcessor")
                final class RegistryProviderImpl implements RegistryProvider {

                    private static final Map<String, Class<?>> REGISTRY = Map.ofEntries(
                """);

        int i = 0;
        int last = entries.size() - 1;

        for (var entry : entries.entrySet()) {
            String key = escapeJavaString(entry.getKey());
            String className = entry.getValue().qualifiedName;

            out.write("        Map.entry(\"" + key + "\", " + className + ".class)");
            if (i++ != last) {
                out.write(",");
            }
            out.write("\n");
        }

        out.write("""
                    );

                    @Override
                    public Map<String, Class<?>> getRegistry() {
                        return REGISTRY;
                    }
                }
                """);
    }

    /**
     * Escapes special characters in strings for Java source code.
     * While our validation restricts keys to safe characters, this provides
     * defense in depth.
     */
    private String escapeJavaString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

//package io.github.cyfko.typeindex.processor;
//
//import com.google.auto.service.AutoService;
//import io.github.cyfko.typeindex.TypeKey;
//
//import javax.annotation.processing.*;
//import javax.lang.model.SourceVersion;
//import javax.lang.model.element.*;
//import javax.tools.Diagnostic;
//import javax.tools.JavaFileObject;
//import java.io.IOException;
//import java.io.Writer;
//import java.util.*;
//import java.util.regex.Pattern;
//
///**
// * Annotation processor responsible for generating a type registry mapping
// * {@link TypeKey} identifiers to fully qualified class names.
// *
// * <p>
// * The processor performs a full validation pass on all {@code @TypeKey} usages:
// * </p>
// * <ul>
// *   <li>Ensures {@code @TypeKey} is applied only to classes.</li>
// *   <li>Verifies that keys contain only allowed characters:
// *       alphanumeric, {@code '.'}, {@code '-'}, {@code '#'}, {@code '_' }.</li>
// *   <li>Enforces global uniqueness of keys within the compilation unit.</li>
// * </ul>
// *
// * <p>
// * At the end of processing, a class named
// * {@code io.github.cyfko.typeindex.providers.RegistryProviderImpl}
// * is generated containing a static, immutable registry mapping keys to
// * Java {@link Class} instances.
// * </p>
// *
// * <p>
// * If any validation error is detected, compilation fails and the registry
// * is not generated. This guarantees that the generated metadata is always
// * consistent with the declared {@code @TypeKey} annotations.
// * </p>
// */
//@AutoService(Processor.class)
//@SupportedAnnotationTypes("io.github.cyfko.typeindex.TypeKey")
//@SupportedSourceVersion(SourceVersion.RELEASE_21)
//public final class TypeIndexProcessor extends AbstractProcessor {
//
//    /**
//     * Allowed key pattern:
//     * alphanumeric plus '.', '-', '#', '_'.
//     * Keys are validated against this pattern to ensure they are safe to
//     * embed in generated Java source code and to keep a constrained key space.
//     */
//    private static final Pattern VALID_KEY_PATTERN =
//            Pattern.compile("^[a-zA-Z0-9.\\-#_]+$");
//
//    /**
//     * Accumulates discovered {@code @TypeKey} entries in a deterministic order.
//     * <p>
//     * The map key is the logical type key ({@link TypeKey#value()}),
//     * and the value is a {@link TypeElementInfo} holding metadata about
//     * the annotated type.
//     * </p>
//     */
//    private final Map<String, TypeElementInfo> entries = new LinkedHashMap<>();
//
//    /**
//     * Indicates whether any validation error has been reported during processing.
//     * <p>
//     * If this flag is {@code true} when processing ends, the registry
//     * will not be generated and a global error message is emitted.
//     * </p>
//     */
//    private boolean hasErrors = false;
//
//    /**
//     * Simple holder for information about a type element annotated with {@code @TypeKey}.
//     * <p>
//     * Storing the {@link Element} reference allows attaching error messages
//     * directly to the original source location for better diagnostics.
//     * </p>
//     */
//    private static class TypeElementInfo {
//        final String qualifiedName;
//        final Element element;
//
//        TypeElementInfo(String qualifiedName, Element element) {
//            this.qualifiedName = qualifiedName;
//            this.element = element;
//        }
//    }
//
//    /**
//     * Main processing entry point for the annotation processor.
//     *
//     * <p>
//     * This method is invoked in one or more rounds. On each round, it scans for
//     * elements annotated with {@link TypeKey}, validates them, and accumulates
//     * valid entries. When {@link RoundEnvironment#processingOver()} is {@code true},
//     * it either generates the registry or emits a final error if validation failed.
//     * </p>
//     *
//     * @param annotations The set of annotation types requested to be processed.
//     * @param env The current round environment providing access to annotated elements.
//     * @return {@code true} to indicate that this processor claims the {@code @TypeKey}
//     *         annotation and no other processor should process it.
//     */
//    @Override
//    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
//        if (annotations.isEmpty()) {
//            // Nothing to do in this round for this processor.
//            return false;
//        }
//
//        Messager log = processingEnv.getMessager();
//        log.printMessage(Diagnostic.Kind.NOTE, "Processing @TypeKey...");
//
//        // Iterate over all elements annotated with @TypeKey in this round.
//        for (Element element : env.getElementsAnnotatedWith(TypeKey.class)) {
//
//            // @TypeKey is only valid on classes.
//            if (element.getKind() != ElementKind.CLASS) {
//                log.printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "@TypeKey can only be applied to classes",
//                        element
//                );
//                hasErrors = true;
//                continue;
//            }
//
//            TypeElement type = (TypeElement) element;
//            TypeKey annotation = type.getAnnotation(TypeKey.class);
//            String key = annotation.value();
//
//            // Validate that the key is not null or blank.
//            if (key == null || key.isBlank()) {
//                log.printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "@TypeKey value cannot be blank",
//                        element
//                );
//                hasErrors = true;
//                continue;
//            }
//
//            // Validate that the key contains only allowed characters.
//            if (!VALID_KEY_PATTERN.matcher(key).matches()) {
//                log.printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "@TypeKey value '" + key + "' contains invalid characters. " +
//                                "Only alphanumeric characters and '.', '-', '#', '_' are allowed",
//                        element
//                );
//                hasErrors = true;
//                continue;
//            }
//
//            // Check for duplicate keys and report both locations.
//            if (entries.containsKey(key)) {
//                TypeElementInfo existing = entries.get(key);
//                String msg = "Duplicate @TypeKey value '" + key + "' found on "
//                        + type.getQualifiedName() + ". Already used by "
//                        + existing.qualifiedName;
//
//                // Report the second occurrence.
//                log.printMessage(Diagnostic.Kind.ERROR, msg, element);
//
//                // Also report on the first occurrence to help the user locate both sides.
//                log.printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "First usage of @TypeKey(\"" + key + "\")",
//                        existing.element
//                );
//
//                hasErrors = true;
//                continue;
//            }
//
//            // Record this valid entry for later registry generation.
//            entries.put(
//                    key,
//                    new TypeElementInfo(type.getQualifiedName().toString(), element)
//            );
//        }
//
//        // When no more rounds will be run, either generate the registry or abort on errors.
//        if (env.processingOver()) {
//            if (hasErrors) {
//                processingEnv.getMessager().printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "Cannot generate registry due to @TypeKey validation errors. " +
//                                "Fix the errors above and recompile."
//                );
//            } else {
//                writeProvider();
//            }
//        }
//
//        // This processor has fully handled @TypeKey.
//        return true;
//    }
//
//    /**
//     * Generates the {@code RegistryProviderImpl} class that exposes the registry.
//     *
//     * <p>
//     * If no {@code @TypeKey} annotations were found, a warning is logged and
//     * an empty registry is generated. Generation failures are reported as
//     * compilation errors.
//     * </p>
//     */
//    private void writeProvider() {
//        Messager log = processingEnv.getMessager();
//
//        if (entries.isEmpty()) {
//            log.printMessage(
//                    Diagnostic.Kind.WARNING,
//                    "No @TypeKey annotations found. Registry will be empty."
//            );
//        }
//
//        try {
//            JavaFileObject file = processingEnv.getFiler()
//                    .createSourceFile("io.github.cyfko.typeindex.providers.RegistryProviderImpl");
//
//            try (Writer writer = file.openWriter()) {
//                writeRegistryClass(writer);
//            }
//
//            log.printMessage(
//                    Diagnostic.Kind.NOTE,
//                    "Generated RegistryProviderImpl with " + entries.size() + " entries"
//            );
//
//        } catch (IOException e) {
//            log.printMessage(
//                    Diagnostic.Kind.ERROR,
//                    "Failed to generate registry: " + e.getMessage()
//            );
//        }
//    }
//
//    /**
//     * Writes the source code of the {@code RegistryProviderImpl} class
//     * to the provided writer.
//     *
//     * <p>
//     * The generated class:
//     * </p>
//     * <ul>
//     *   <li>Is package-private and {@code final}.</li>
//     *   <li>Implements {@code RegistryProvider}.</li>
//     *   <li>Initializes a static, immutable {@code Map<String, Class<?>>} via {@code Map.ofEntries}.</li>
//     * </ul>
//     *
//     * @param out Writer to which the Java source code will be written.
//     * @throws IOException If an I/O error occurs while writing the file.
//     */
//    private void writeRegistryClass(Writer out) throws IOException {
//        out.write("""
//                package io.github.cyfko.typeindex.providers;
//
//                import java.util.Map;
//                import javax.annotation.processing.Generated;
//
//                @Generated("io.github.cyfko.typeindex.processor.TypeIndexProcessor")
//                final class RegistryProviderImpl implements RegistryProvider {
//
//                    private static final Map<String, Class<?>> REGISTRY = Map.ofEntries(
//                """);
//
//        int i = 0;
//        int last = entries.size() - 1;
//
//        // Generate each registry entry as Map.entry("key", SomeClass.class)
//        for (var entry : entries.entrySet()) {
//            String key = escapeJavaString(entry.getKey());
//            String className = entry.getValue().qualifiedName;
//
//            out.write("        Map.entry(\"" + key + "\", " + className + ".class)");
//            if (i++ != last) {
//                out.write(",");
//            }
//            out.write("\n");
//        }
//
//        out.write("""
//                    );
//
//                    @Override
//                    public Map<String, Class<?>> getRegistry() {
//                        return REGISTRY;
//                    }
//                }
//                """);
//    }
//
//    /**
//     * Escapes special characters in a string so that it can be safely embedded
//     * as a Java string literal in generated source code.
//     *
//     * <p>
//     * While key validation already restricts values to a safe character set,
//     * this method provides an extra layer of defense and makes the generator
//     * more robust against future changes.
//     * </p>
//     *
//     * @param s Raw string to escape.
//     * @return Escaped representation suitable for use inside a Java string literal.
//     */
//    private String escapeJavaString(String s) {
//        return s.replace("\\", "\\\\")
//                .replace("\"", "\\\"")
//                .replace("\n", "\\n")
//                .replace("\r", "\\r")
//                .replace("\t", "\\t");
//    }
//}
