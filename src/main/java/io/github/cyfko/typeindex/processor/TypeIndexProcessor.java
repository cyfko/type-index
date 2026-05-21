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
        Messager log = processingEnv.getMessager();

        Set<? extends Element> annotatedElements = env.getElementsAnnotatedWith(TypeKey.class);

        if (!annotatedElements.isEmpty()) {
            hasProcessedAnnotations = true;
            log.printMessage(Diagnostic.Kind.NOTE, "Processing @TypeKey...");
        }

        for (Element element : annotatedElements) {

            if (! (element.getKind() == ElementKind.CLASS ||
                    element.getKind() == ElementKind.RECORD ||
                    element.getKind() == ElementKind.ENUM)
            ) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey can only be applied to classes, records or enums", element);
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
                public final class RegistryProviderImpl implements RegistryProvider {

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
