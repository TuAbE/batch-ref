package io.github.batchref.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BatchQueryMethodProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsMatchingBatchMethodName() throws IOException {
        CompilationResult result = compile(
                "sample.ValidRelationQueryService",
                """
                        package sample;

                        import io.github.batchref.annotation.BatchQueryMethod;
                        import java.util.Collection;
                        import java.util.LinkedHashMap;
                        import java.util.Map;

                        class ValidRelationQueryService {

                            @BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIds")
                            Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
                                return null;
                            }

                            private Map<Long, Relation> getActiveRelationMapByWorkerProjectIds(
                                    Collection<Long> workerProjectIds
                            ) {
                                return new LinkedHashMap<>();
                            }

                            static class Relation {
                            }
                        }
                        """
        );

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsWrongBatchMethodName() throws IOException {
        CompilationResult result = compile(
                "sample.InvalidRelationQueryService",
                """
                        package sample;

                        import io.github.batchref.annotation.BatchQueryMethod;
                        import java.util.Collection;
                        import java.util.LinkedHashMap;
                        import java.util.Map;

                        class InvalidRelationQueryService {

                            @BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIdList")
                            Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
                                return null;
                            }

                            private Map<Long, Relation> getActiveRelationMapByWorkerProjectIds(
                                    Collection<Long> workerProjectIds
                            ) {
                                return new LinkedHashMap<>();
                            }

                            static class Relation {
                            }
                        }
                        """
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .anySatisfy(message -> assertThat(message)
                        .contains("Cannot resolve @BatchQueryMethod.batchMethod")
                        .contains("getActiveRelationMapByWorkerProjectIdList"));
    }

    @Test
    void rejectsMatchingNameWithInvalidSignature() throws IOException {
        CompilationResult result = compile(
                "sample.InvalidSignatureQueryService",
                """
                        package sample;

                        import io.github.batchref.annotation.BatchQueryMethod;
                        import java.util.Collection;
                        import java.util.List;

                        class InvalidSignatureQueryService {

                            @BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIds")
                            Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
                                return null;
                            }

                            private List<Relation> getActiveRelationMapByWorkerProjectIds(
                                    Collection<Long> workerProjectIds
                            ) {
                                return List.of();
                            }

                            static class Relation {
                            }
                        }
                        """
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .anySatisfy(message -> assertThat(message)
                        .contains("was found, but it must have exactly one Collection parameter and return Map")
                        .contains("java.util.List"));
    }

    private CompilationResult compile(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8
        )) {
            List<String> options = List.of(
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    tempDir.toString()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(new SourceFile(className, source))
            );
            task.setProcessors(List.of(new BatchQueryMethodProcessor()));
            Boolean success = task.call();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                    .toList();
            return new CompilationResult(Boolean.TRUE.equals(success), errors);
        }
    }

    private record CompilationResult(boolean success, List<String> errors) {
    }

    private static final class SourceFile extends SimpleJavaFileObject {

        private final String source;

        private SourceFile(String className, String source) {
            super(
                    URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE
            );
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
