package io.github.batchref.idea;

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
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BatchQueryMethodSupport {

    private static final String BATCH_QUERY_METHOD = "BatchQueryMethod";
    private static final String BATCH_QUERY_METHOD_FQN = "io.github.batchref.annotation.BatchQueryMethod";

    private BatchQueryMethodSupport() {
    }

    static @Nullable Context contextOf(@NotNull PsiLiteralExpression literalExpression) {
        if (literalValue(literalExpression) == null) {
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

    static boolean isBatchQueryMethodAnnotation(@NotNull PsiAnnotation annotation) {
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

    static @Nullable PsiClass targetClass(@NotNull PsiAnnotation annotation, @NotNull PsiElement fallbackElement) {
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

        return PsiTreeUtil.getParentOfType(fallbackElement, PsiClass.class);
    }

    static @Nullable String declaredBatchMethodName(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue batchMethodValue = annotation.findDeclaredAttributeValue("batchMethod");
        String batchMethodName = literalValue(batchMethodValue);
        if (batchMethodName != null) {
            return batchMethodName;
        }

        return literalValue(annotation.findDeclaredAttributeValue("method"));
    }

    static @Nullable PsiMethod findBatchMethod(@NotNull Context context, @NotNull String methodName) {
        if (methodName.isBlank()) {
            return null;
        }

        for (PsiMethod method : batchMethods(context.targetClass())) {
            if (methodName.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    static List<PsiMethod> batchMethods(@NotNull PsiClass targetClass) {
        Map<String, PsiMethod> methodsBySignature = new LinkedHashMap<>();
        for (PsiMethod method : targetClass.getAllMethods()) {
            if (isBatchMethod(method)) {
                methodsBySignature.putIfAbsent(method.getSignature(PsiSubstitutor.EMPTY).toString(), method);
            }
        }
        return new ArrayList<>(methodsBySignature.values());
    }

    static boolean isBatchMethod(@NotNull PsiMethod method) {
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

    static @Nullable String literalValue(@Nullable PsiElement element) {
        if (!(element instanceof PsiLiteralExpression literalExpression)) {
            return null;
        }

        Object value = literalExpression.getValue();
        return value instanceof String stringValue ? stringValue : null;
    }

    static String methodText(@NotNull PsiMethod method) {
        PsiParameter parameter = method.getParameterList().getParameter(0);
        String parameterText = parameter == null ? "" : parameter.getType().getPresentableText();
        PsiType returnType = method.getReturnType();
        String returnText = returnType == null ? "void" : returnType.getPresentableText();
        return "(" + parameterText + ") -> " + returnText;
    }

    static boolean sameClass(@NotNull PsiClass left, @Nullable PsiClass right) {
        return right != null && left.getManager().areElementsEquivalent(left, right);
    }

    private static boolean isMethodAttribute(@Nullable String attributeName) {
        return "batchMethod".equals(attributeName) || "method".equals(attributeName);
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

    record Context(@NotNull PsiClass targetClass) {
    }
}
