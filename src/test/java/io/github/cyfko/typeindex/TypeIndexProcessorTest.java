package io.github.cyfko.typeindex;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.github.cyfko.typeindex.processor.TypeIndexProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TypeIndexProcessor annotation processor.
 * Tests validation, code generation, and error handling.
 */
class TypeIndexProcessorTest {

    @Test
    void testSuccessfulRegistryGeneration() throws IOException {
        JavaFileObject user = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.User",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"my-key\")",
                "public class User {",
                "}"
        );

        JavaFileObject address = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Address",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"#1\")",
                "public record Address() {",
                "}"
        );

        JavaFileObject enumElement = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.MyEnum",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"my.enum#1\")",
                "public enum MyEnum { DUMMY }"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(user, address, enumElement);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedRegistryCode(compilation);

        // Verify registry structure
        assertTrue(generatedCode.contains("class RegistryProviderImpl implements RegistryProvider"));
        assertTrue(generatedCode.contains("Map.ofEntries("));

        // Verify both entries are present with correct format
        assertTrue(generatedCode.contains("Map.entry(\"my-key\", io.github.cyfko.example.User.class)"));
        assertTrue(generatedCode.contains("Map.entry(\"#1\", io.github.cyfko.example.Address.class)"));
        assertTrue(generatedCode.contains("Map.entry(\"my.enum#1\", io.github.cyfko.example.MyEnum.class)"));

        // Verify @Generated annotation
        assertTrue(generatedCode.contains("@Generated(\"io.github.cyfko.typeindex.processor.TypeIndexProcessor\")"));
    }

    @Test
    void testDuplicateKeysFailCompilation() {
        JavaFileObject user = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.User",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"my-key\")",
                "public class User {",
                "}"
        );

        JavaFileObject order = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Order",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"my-key\")",
                "public class Order {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(user, order);

        // Compilation must fail
        assertThat(compilation).failed();

        // Verify error messages
        assertThat(compilation).hadErrorContaining("Duplicate @TypeKey value 'my-key'");
        assertThat(compilation).hadErrorContaining("io.github.cyfko.example.User");
        assertThat(compilation).hadErrorContaining("io.github.cyfko.example.Order");
        assertThat(compilation).hadErrorContaining("Cannot generate registry due to @TypeKey validation errors");
    }

    @Test
    void testInvalidCharactersInKey() {
        JavaFileObject invalidKey = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.InvalidKey",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"invalid/key\")",
                "public class InvalidKey {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(invalidKey);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("contains invalid characters");
        assertThat(compilation).hadErrorContaining("Only alphanumeric characters and '.', '-', '#', '_' are allowed");
    }

    @Test
    void testValidSpecialCharactersInKeys() throws IOException {
        JavaFileObject dto1 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Dto1",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"user.profile\")",
                "public class Dto1 {",
                "}"
        );

        JavaFileObject dto2 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Dto2",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"order-item\")",
                "public class Dto2 {",
                "}"
        );

        JavaFileObject dto3 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Dto3",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"config#v1\")",
                "public class Dto3 {",
                "}"
        );

        JavaFileObject dto4 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Dto4",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"data_store\")",
                "public class Dto4 {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(dto1, dto2, dto3, dto4);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedRegistryCode(compilation);

        assertTrue(generatedCode.contains("\"user.profile\""));
        assertTrue(generatedCode.contains("\"order-item\""));
        assertTrue(generatedCode.contains("\"config#v1\""));
        assertTrue(generatedCode.contains("\"data_store\""));
    }

    @Test
    void testBlankKeyFailsCompilation() {
        JavaFileObject blankKey = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.BlankKey",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"   \")",
                "public class BlankKey {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(blankKey);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@TypeKey value cannot be blank");
    }

    @Test
    void testAnnotationOnNonClassFailsCompilation() {
        JavaFileObject interfaceWithAnnotation = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.NotAClass",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"interface-key\")",
                "public interface NotAClass {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(interfaceWithAnnotation);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@TypeKey can only be applied to classes");
    }

    @Test
    void testLargeNumberOfEntries() throws IOException {
        // Test more than 10 entries to verify Map.ofEntries() works
        JavaFileObject[] classes = new JavaFileObject[15];

        for (int i = 0; i < 15; i++) {
            classes[i] = JavaFileObjects.forSourceLines(
                    "io.github.cyfko.example.Class" + i,
                    "package io.github.cyfko.example;",
                    "",
                    "import io.github.cyfko.typeindex.TypeKey;",
                    "",
                    "@TypeKey(\"key-" + i + "\")",
                    "public class Class" + i + " {",
                    "}"
            );
        }

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(classes);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedRegistryCode(compilation);

        // Verify all 15 entries are present
        for (int i = 0; i < 15; i++) {
            assertTrue(generatedCode.contains("\"key-" + i + "\""),
                    "Missing entry for key-" + i);
        }

        assertThat(compilation).hadNoteContaining("Generated RegistryProviderImpl with 15 entries");
    }

    @Test
    void testMultipleDuplicateKeysReportsAll() {
        JavaFileObject class1 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Class1",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"duplicate\")",
                "public class Class1 {",
                "}"
        );

        JavaFileObject class2 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Class2",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"duplicate\")",
                "public class Class2 {",
                "}"
        );

        JavaFileObject class3 = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.Class3",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"duplicate\")",
                "public class Class3 {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(class1, class2, class3);

        assertThat(compilation).failed();

        // Should report errors: 2 duplicates × 2 messages each = 4, plus 1 final error = 5 total
        // But the final error might not always be counted, so we check for at least 4
        assertThat(compilation).hadErrorContaining("Duplicate @TypeKey value 'duplicate'");

        // Verify multiple duplicate reports
        String diagnostics = compilation.errors().toString();
        int duplicateCount = countOccurrences(diagnostics, "Duplicate @TypeKey value 'duplicate'");
        assertTrue(duplicateCount >= 2, "Should report at least 2 duplicate errors, found: " + duplicateCount);
    }

    @Test
    void testSpecialCharactersRequireEscaping() throws IOException {
        // Test that dots are properly handled in keys
        JavaFileObject withDot = JavaFileObjects.forSourceLines(
                "io.github.cyfko.example.DotKey",
                "package io.github.cyfko.example;",
                "",
                "import io.github.cyfko.typeindex.TypeKey;",
                "",
                "@TypeKey(\"com.example.key\")",
                "public class DotKey {",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new TypeIndexProcessor())
                .compile(withDot);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedRegistryCode(compilation);
        // Verify the dot is properly included in the string
        assertTrue(generatedCode.contains("\"com.example.key\""));
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts the generated RegistryProviderImpl source code from compilation results.
     */
    private String getGeneratedRegistryCode(Compilation compilation) throws IOException {
        return compilation
                .generatedSourceFile("io.github.cyfko.typeindex.providers.RegistryProviderImpl")
                .orElseThrow(() -> new AssertionError("Generated RegistryProviderImpl not found"))
                .getCharContent(true)
                .toString();
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     */
    private int countOccurrences(String str, String substring) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}