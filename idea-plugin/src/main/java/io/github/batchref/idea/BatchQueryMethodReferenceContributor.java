package io.github.batchref.idea;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

public final class BatchQueryMethodReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(PsiLiteralExpression.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element,
                            @NotNull ProcessingContext context
                    ) {
                        if (!(element instanceof PsiLiteralExpression literalExpression)) {
                            return PsiReference.EMPTY_ARRAY;
                        }

                        BatchQueryMethodSupport.Context referenceContext =
                                BatchQueryMethodSupport.contextOf(literalExpression);
                        if (referenceContext == null) {
                            return PsiReference.EMPTY_ARRAY;
                        }

                        return new PsiReference[]{new BatchQueryMethodReference(literalExpression, referenceContext)};
                    }
                }
        );
    }
}
