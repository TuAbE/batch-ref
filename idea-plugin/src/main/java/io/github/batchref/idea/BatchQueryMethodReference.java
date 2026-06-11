package io.github.batchref.idea;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class BatchQueryMethodReference extends PsiReferenceBase<PsiLiteralExpression> {

    private final BatchQueryMethodSupport.Context context;

    BatchQueryMethodReference(@NotNull PsiLiteralExpression element, @NotNull BatchQueryMethodSupport.Context context) {
        super(element, ElementManipulators.getValueTextRange(element), false);
        this.context = context;
    }

    @Override
    public @Nullable PsiElement resolve() {
        String methodName = BatchQueryMethodSupport.literalValue(myElement);
        if (methodName == null) {
            return null;
        }

        return BatchQueryMethodSupport.findBatchMethod(context, methodName);
    }

    @Override
    public Object @NotNull [] getVariants() {
        List<LookupElementBuilder> variants = new ArrayList<>();
        for (PsiMethod method : BatchQueryMethodSupport.batchMethods(context.targetClass())) {
            variants.add(LookupElementBuilder
                    .create(method.getName())
                    .withIcon(method.getIcon(0))
                    .withTypeText(BatchQueryMethodSupport.methodText(method), true));
        }
        return variants.toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return ElementManipulators.handleContentChange(myElement, getRangeInElement(), newElementName);
    }
}
