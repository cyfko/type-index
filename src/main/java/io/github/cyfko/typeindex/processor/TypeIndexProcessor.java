package io.github.cyfko.typeindex.processor;

import com.google.auto.service.AutoService;
import io.github.cyfko.typeindex.TypeKey;
import io.github.cyfko.typeindex.TypeKeyConfig;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
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
 *     <li>that @TypeKey is used only on classes, records, or enums</li>
 *     <li>that @TypeKeyConfig is used only on interfaces</li>
 *     <li>that methods in @TypeKeyConfig interfaces have zero parameters and return a concrete class/record/enum</li>
 *     <li>that keys contain only allowed characters: alphanumeric, '.', '-', '#', '_'</li>
 *     <li>that keys and target types are globally unique</li>
 * </ul>
 * At the end of processing, a class named
 * {@code io.github.cyfko.typeindex.providers.RegistryProviderImpl}
 * is generated containing a static, immutable registry.
 * <p>
 * Compilation will fail if any validation errors are detected.
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "io.github.cyfko.typeindex.TypeKey",
        "io.github.cyfko.typeindex.TypeKeyConfig"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class TypeIndexProcessor extends AbstractProcessor {

    private static final Pattern VALID_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z0-9.\\-#_]+$");

    private final Map<String, RegistryEntry> keyToEntry = new LinkedHashMap<>();
    private final Map<String, RegistryEntry> classToEntry = new LinkedHashMap<>();
    private boolean hasErrors = false;
    private boolean hasProcessedAnnotations = false;

    private static class RegistryEntry {
        final String key;
        final String qualifiedName;
        final Element element;
        final String source; // "direct" or "config"
        final String declSource; // Description of where it was declared

        RegistryEntry(String key, String qualifiedName, Element element, String source, String declSource) {
            this.key = key;
            this.qualifiedName = qualifiedName;
            this.element = element;
            this.source = source;
            this.declSource = declSource;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        Messager log = processingEnv.getMessager();

        // 1. Process Direct @TypeKey Annotations
        Set<? extends Element> directElements = env.getElementsAnnotatedWith(TypeKey.class);
        for (Element element : directElements) {
            // Skip method annotations, as they are part of @TypeKeyConfig and processed below.
            if (element.getKind() == ElementKind.METHOD) {
                continue;
            }

            if (!(element.getKind() == ElementKind.CLASS ||
                    element.getKind() == ElementKind.RECORD ||
                    element.getKind() == ElementKind.ENUM)) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey can only be applied to classes, records or enums", element);
                hasErrors = true;
                continue;
            }

            hasProcessedAnnotations = true;
            TypeElement type = (TypeElement) element;
            TypeKey annotation = type.getAnnotation(TypeKey.class);
            String key = annotation.value();

            if (key == null || key.isBlank()) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey value cannot be blank", element);
                hasErrors = true;
                continue;
            }

            if (!VALID_KEY_PATTERN.matcher(key).matches()) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKey value '" + key + "' contains invalid characters. " +
                                "Only alphanumeric characters and '.', '-', '#', '_' are allowed",
                        element);
                hasErrors = true;
                continue;
            }

            String qualifiedName = type.getQualifiedName().toString();
            registerAndValidate(key, qualifiedName, element, "direct", "class " + qualifiedName);
        }

        // 2. Process @TypeKeyConfig External Configurations
        Set<? extends Element> configElements = env.getElementsAnnotatedWith(TypeKeyConfig.class);
        for (Element element : configElements) {
            if (element.getKind() != ElementKind.INTERFACE) {
                log.printMessage(Diagnostic.Kind.ERROR,
                        "@TypeKeyConfig can only be applied to interfaces", element);
                hasErrors = true;
                continue;
            }

            hasProcessedAnnotations = true;
            TypeElement configInterface = (TypeElement) element;
            String configName = configInterface.getQualifiedName().toString();

            for (Element enclosed : configInterface.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }

                ExecutableElement method = (ExecutableElement) enclosed;
                TypeKey typeKeyAnnotation = method.getAnnotation(TypeKey.class);
                if (typeKeyAnnotation == null) {
                    continue; // Skip methods not annotated with @TypeKey
                }

                // Validate parameters
                if (!method.getParameters().isEmpty()) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "Methods in @TypeKeyConfig interfaces must have zero parameters", method);
                    hasErrors = true;
                    continue;
                }

                // Validate return type (must be declared, e.g. not primitive, void or array)
                TypeMirror returnType = method.getReturnType();
                if (returnType.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "Return type of method in @TypeKeyConfig must be a class, record or enum", method);
                    hasErrors = true;
                    continue;
                }

                DeclaredType declaredType = (DeclaredType) returnType;

                // Validate no parameterized generic type arguments (e.g. List<String>)
                if (!declaredType.getTypeArguments().isEmpty()) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "Return type of method in @TypeKeyConfig cannot be a parameterized generic type", method);
                    hasErrors = true;
                    continue;
                }

                TypeElement returnElement = (TypeElement) processingEnv.getTypeUtils().asElement(returnType);
                if (returnElement == null || !(returnElement.getKind() == ElementKind.CLASS ||
                        returnElement.getKind() == ElementKind.RECORD ||
                        returnElement.getKind() == ElementKind.ENUM)) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "Return type of method in @TypeKeyConfig must be a class, record or enum", method);
                    hasErrors = true;
                    continue;
                }

                String key = typeKeyAnnotation.value();

                if (key == null || key.isBlank()) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "@TypeKey value cannot be blank", method);
                    hasErrors = true;
                    continue;
                }

                if (!VALID_KEY_PATTERN.matcher(key).matches()) {
                    log.printMessage(Diagnostic.Kind.ERROR,
                            "@TypeKey value '" + key + "' contains invalid characters. " +
                                    "Only alphanumeric characters and '.', '-', '#', '_' are allowed",
                            method);
                    hasErrors = true;
                    continue;
                }

                String targetClassName = returnElement.getQualifiedName().toString();
                String declSource = configName + "." + method.getSimpleName() + "()";
                registerAndValidate(key, targetClassName, method, "config", declSource);
            }
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
                            "No @TypeKey or @TypeKeyConfig annotations found. Registry will be empty.");
                }
                writeProvider();
            }
        }

        return true;
    }

    private void registerAndValidate(String key, String qualifiedName, Element element, String source, String declSource) {
        Messager log = processingEnv.getMessager();
        RegistryEntry newEntry = new RegistryEntry(key, qualifiedName, element, source, declSource);

        // 1. Check Key Conflict (same key points to different classes)
        if (keyToEntry.containsKey(key)) {
            RegistryEntry existing = keyToEntry.get(key);
            if (!existing.qualifiedName.equals(qualifiedName)) {
                String msg = "Duplicate @TypeKey value '" + key + "' found on "
                        + declSource + ". Already used by " + existing.declSource;
                log.printMessage(Diagnostic.Kind.ERROR, msg, element);
                log.printMessage(Diagnostic.Kind.ERROR, "First usage of @TypeKey(\"" + key + "\")", existing.element);
                hasErrors = true;
                return;
            } else {
                // Same key, same class. Check direct/config conflict
                if (existing.source.equals("direct") || source.equals("direct")) {
                    String msg = "Conflict: Type '" + qualifiedName + "' is registered both directly on the class and via @TypeKeyConfig on " + declSource;
                    log.printMessage(Diagnostic.Kind.ERROR, msg, element);
                    log.printMessage(Diagnostic.Kind.ERROR, "Conflicting direct registration", existing.element);
                    hasErrors = true;
                    return;
                }
                // Both are configs with the same mapping -> allowed, do not register again.
                return;
            }
        }

        // 2. Check Type Conflict (same class registered with different keys)
        if (classToEntry.containsKey(qualifiedName)) {
            RegistryEntry existing = classToEntry.get(qualifiedName);
            if (!existing.key.equals(key)) {
                String msg = "Type '" + qualifiedName + "' is registered with conflicting keys: '"
                        + key + "' (on " + declSource + ") and '" + existing.key + "' (on " + existing.declSource + ")";
                log.printMessage(Diagnostic.Kind.ERROR, msg, element);
                log.printMessage(Diagnostic.Kind.ERROR, "Conflicting key registration", existing.element);
                hasErrors = true;
                return;
            } else {
                // Same key, same class. Check direct/config conflict
                if (existing.source.equals("direct") || source.equals("direct")) {
                    String msg = "Conflict: Type '" + qualifiedName + "' is registered both directly on the class and via @TypeKeyConfig on " + declSource;
                    log.printMessage(Diagnostic.Kind.ERROR, msg, element);
                    log.printMessage(Diagnostic.Kind.ERROR, "Conflicting direct registration", existing.element);
                    hasErrors = true;
                    return;
                }
                // Both are configs with the same mapping -> allowed.
                return;
            }
        }

        keyToEntry.put(key, newEntry);
        classToEntry.put(qualifiedName, newEntry);
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
                    "Generated RegistryProviderImpl with " + keyToEntry.size() + " entries");

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
        int last = keyToEntry.size() - 1;

        for (var entry : keyToEntry.entrySet()) {
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

    private String escapeJavaString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

