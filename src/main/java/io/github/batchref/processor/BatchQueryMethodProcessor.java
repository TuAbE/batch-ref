package io.github.batchref.processor;

import io.github.batchref.annotation.BatchQueryMethod;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes("io.github.batchref.annotation.BatchQueryMethod")
public final class BatchQueryMethodProcessor extends AbstractProcessor {

    private Types types;
    private Messager messager;
    private TypeMirror collectionType;
    private TypeMirror mapType;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        types = processingEnv.getTypeUtils();
        messager = processingEnv.getMessager();
        collectionType = erasedType("java.util.Collection");
        mapType = erasedType("java.util.Map");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv
    ) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BatchQueryMethod.class)) {
            validate(element);
        }
        return false;
    }

    private void validate(Element element) {
        if (!(element instanceof ExecutableElement queryMethod)) {
            error(element, "@BatchQueryMethod can only be used on methods.");
            return;
        }

        BatchQueryMethod annotation = queryMethod.getAnnotation(BatchQueryMethod.class);
        if (annotation == null) {
            return;
        }

        String batchMethodName = annotation.batchMethod();
        if (batchMethodName == null || batchMethodName.isBlank()) {
            error(queryMethod, "@BatchQueryMethod.batchMethod must not be blank.");
            return;
        }

        if (!(queryMethod.getEnclosingElement() instanceof TypeElement ownerClass)) {
            error(queryMethod, "@BatchQueryMethod must be declared inside a class.");
            return;
        }

        BatchMethodSearchResult result = findBatchMethod(ownerClass, batchMethodName);
        if (result.foundValidMethod()) {
            return;
        }

        if (result.foundNameOnly()) {
            error(
                    queryMethod,
                    "@BatchQueryMethod.batchMethod '" + batchMethodName
                            + "' was found, but it must have exactly one Collection parameter and return Map. "
                            + "Candidates: " + result.candidateDescriptions()
            );
            return;
        }

        error(
                queryMethod,
                "Cannot resolve @BatchQueryMethod.batchMethod '" + batchMethodName
                        + "' on " + ownerClass.getQualifiedName()
                        + ". Expected a method with exactly one Collection parameter and Map return type."
        );
    }

    private BatchMethodSearchResult findBatchMethod(TypeElement ownerClass, String batchMethodName) {
        boolean foundNameOnly = false;
        List<String> candidateDescriptions = new ArrayList<>();

        TypeElement currentClass = ownerClass;
        while (currentClass != null) {
            for (Element enclosedElement : currentClass.getEnclosedElements()) {
                if (enclosedElement.getKind() != ElementKind.METHOD) {
                    continue;
                }

                ExecutableElement method = (ExecutableElement) enclosedElement;
                if (!method.getSimpleName().contentEquals(batchMethodName)) {
                    continue;
                }

                if (isValidBatchMethod(method)) {
                    return BatchMethodSearchResult.valid();
                }

                foundNameOnly = true;
                candidateDescriptions.add(describe(method));
            }

            currentClass = superClass(currentClass);
        }

        return new BatchMethodSearchResult(false, foundNameOnly, candidateDescriptions);
    }

    private boolean isValidBatchMethod(ExecutableElement method) {
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.size() != 1) {
            return false;
        }

        return isAssignableToErased(parameters.get(0).asType(), collectionType)
                && isAssignableToErased(method.getReturnType(), mapType);
    }

    private boolean isAssignableToErased(TypeMirror actualType, TypeMirror expectedSuperType) {
        if (expectedSuperType == null || actualType == null || actualType.getKind().isPrimitive()) {
            return false;
        }

        return types.isAssignable(types.erasure(actualType), expectedSuperType);
    }

    private TypeMirror erasedType(String qualifiedName) {
        TypeElement typeElement = processingEnv.getElementUtils().getTypeElement(qualifiedName);
        if (typeElement == null) {
            return null;
        }
        return types.erasure(typeElement.asType());
    }

    private TypeElement superClass(TypeElement typeElement) {
        TypeMirror superClass = typeElement.getSuperclass();
        if (superClass == null || superClass.getKind() == TypeKind.NONE) {
            return null;
        }

        Element superElement = types.asElement(superClass);
        return superElement instanceof TypeElement superTypeElement ? superTypeElement : null;
    }

    private String describe(ExecutableElement method) {
        return method.getSimpleName()
                + "("
                + method.getParameters().stream()
                .map(parameter -> parameter.asType().toString())
                .reduce((left, right) -> left + ", " + right)
                .orElse("")
                + ") -> "
                + method.getReturnType();
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record BatchMethodSearchResult(
            boolean foundValidMethod,
            boolean foundNameOnly,
            List<String> candidateDescriptions
    ) {

        private static BatchMethodSearchResult valid() {
            return new BatchMethodSearchResult(true, true, List.of());
        }
    }
}
