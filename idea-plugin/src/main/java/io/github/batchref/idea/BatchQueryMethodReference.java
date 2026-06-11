package io.github.batchref.idea;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BatchQueryMethodReference extends PsiReferenceBase<PsiLiteralExpression> {

    private static final String BATCH_QUERY_METHOD = "BatchQueryMethod";
    private static final String BATCH_QUERY_METHOD_FQN = "io.github.batchref.annotation.BatchQueryMethod";

    private final Context context;

    BatchQueryMethodReference(@NotNull PsiLiteralExpression element, @NotNull Context context) {
        super(element, ElementManipulators.getValueTextRange(element), false);
        this.context = context;
    }

    @Override
    public @Nullable PsiElement resolve() {
        String methodName = literalValue(myElement);
        if (methodName == null || methodName.isBlank()) {
            return null;
        }

        for (PsiMethod method : batchMethods(context.targetClass())) {
            if (methodName.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    @Override
    public Object @NotNull [] getVariants() {
        List<LookupElementBuilder> variants = new ArrayList<>();
        for (PsiMethod method : batchMethods(context.targetClass())) {
            variants.add(LookupElementBuilder
                    .create(method.getName())
                    .withIcon(method.getIcon(0))
                    .withTypeText(methodText(method), true));
        }
        return variants.toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return ElementManipulators.handleContentChange(myElement, getRangeInElement(), newElementName);
    }

    static @Nullable Context contextOf(@NotNull PsiLiteralExpression literalExpression) {
        if (!(literalExpression.getValue() instanceof String)) {
            return null;
        }

        PsiElement parent = literalExpression.getParent();
        if (!(parent instanceof PsiNameValuePair nameValuePair)) {
            return null;
        }

        String attributeName = nameValuePair.getName();
        if (!isMethodAttribute(attributeName)) {
            return null;
        }

        PsiElement annotationElement = nameValuePair.getParent() == null ? null : nameValuePair.getParent().getParent();
        if (!(annotationElement instanceof PsiAnnotation annotation) || !isBatchQueryMethodAnnotation(annotation)) {
            return null;
        }

        PsiClass targetClass = targetClass(annotation, literalExpression);
        if (targetClass == null) {
            return null;
        }

        return new Context(targetClass);
    }

    private static boolean isMethodAttribute(@Nullable String attributeName) {
        return "batchMethod".equals(attributeName) || "method".equals(attributeName);
    }

    private static boolean isBatchQueryMethodAnnotation(@NotNull PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (BATCH_QUERY_METHOD_FQN.equals(qualifiedName)) {
            return true;
        }

        if (qualifiedName != null) {
            return false;
        }

        return annotation.getNameReferenceElement() != null
                && BATCH_QUERY_METHOD.equals(annotation.getNameReferenceElement().getReferenceName());
    }

    private static @Nullable PsiClass targetClass(
            @NotNull PsiAnnotation annotation,
            @NotNull PsiLiteralExpression literalExpression
    ) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("targetClass");
        if (value instanceof PsiClassObjectAccessExpression classObjectAccessExpression) {
            PsiType operandType = classObjectAccessExpression.getOperand().getType();
            if (operandType instanceof PsiClassType classType) {
                PsiClass resolvedClass = classType.resolve();
                if (resolvedClass != null) {
                    return resolvedClass;
                }
            }
        }

        return PsiTreeUtil.getParentOfType(literalExpression, PsiClass.class);
    }

    private static List<PsiMethod> batchMethods(@NotNull PsiClass targetClass) {
        Map<String, PsiMethod> methodsBySignature = new LinkedHashMap<>();
        for (PsiMethod method : targetClass.getAllMethods()) {
            if (isBatchMethod(method)) {
                methodsBySignature.putIfAbsent(method.getSignature(PsiSubstitutorHolder.EMPTY).toString(), method);
            }
        }
        return new ArrayList<>(methodsBySignature.values());
    }

    private static boolean isBatchMethod(@NotNull PsiMethod method) {
        if (method.isConstructor() || method.getParameterList().getParametersCount() != 1) {
            return false;
        }

        PsiType returnType = method.getReturnType();
        if (returnType == null || !isMapType(returnType)) {
            return false;
        }

        PsiParameter parameter = method.getParameterList().getParameter(0);
        return parameter != null && isCollectionType(parameter.getType());
    }

    private static boolean isCollectionType(@NotNull PsiType type) {
        String canonicalText = type.getCanonicalText();
        return canonicalText.equals("java.util.Collection")
                || canonicalText.startsWith("java.util.Collection<")
                || canonicalText.equals("java.util.List")
                || canonicalText.startsWith("java.util.List<")
                || canonicalText.equals("java.util.Set")
                || canonicalText.startsWith("java.util.Set<");
    }

    private static boolean isMapType(@NotNull PsiType type) {
        String canonicalText = type.getCanonicalText();
        return canonicalText.equals("java.util.Map")
                || canonicalText.startsWith("java.util.Map<");
    }

    private static @Nullable String literalValue(@NotNull PsiLiteralExpression literalExpression) {
        Object value = literalExpression.getValue();
        return value instanceof String stringValue ? stringValue : null;
    }

    private static String methodText(@NotNull PsiMethod method) {
        PsiParameter parameter = method.getParameterList().getParameter(0);
        String parameterText = parameter == null ? "" : parameter.getType().getPresentableText();
        PsiType returnType = method.getReturnType();
        String returnText = returnType == null ? "void" : returnType.getPresentableText();
        return "(" + parameterText + ") -> " + returnText;
    }

    record Context(@NotNull PsiClass targetClass) {
    }

    private static final class PsiSubstitutorHolder {
        private static final com.intellij.psi.PsiSubstitutor EMPTY = com.intellij.psi.PsiSubstitutor.EMPTY;
    }
}
