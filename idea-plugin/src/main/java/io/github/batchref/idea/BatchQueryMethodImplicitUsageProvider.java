package io.github.batchref.idea;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public final class BatchQueryMethodImplicitUsageProvider implements ImplicitUsageProvider {

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        if (!(element instanceof PsiMethod method) || !BatchQueryMethodSupport.isBatchMethod(method)) {
            return false;
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }

        for (PsiAnnotation annotation : PsiTreeUtil.findChildrenOfType(containingClass, PsiAnnotation.class)) {
            if (!BatchQueryMethodSupport.isBatchQueryMethodAnnotation(annotation)) {
                continue;
            }

            PsiClass targetClass = BatchQueryMethodSupport.targetClass(annotation, annotation);
            if (!BatchQueryMethodSupport.sameClass(containingClass, targetClass)) {
                continue;
            }

            if (method.getName().equals(BatchQueryMethodSupport.declaredBatchMethodName(annotation))) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }
}
