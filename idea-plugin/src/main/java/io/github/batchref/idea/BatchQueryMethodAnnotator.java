package io.github.batchref.idea;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;

public final class BatchQueryMethodAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiLiteralExpression literalExpression)) {
            return;
        }

        BatchQueryMethodSupport.Context context = BatchQueryMethodSupport.contextOf(literalExpression);
        if (context == null) {
            return;
        }

        String methodName = BatchQueryMethodSupport.literalValue(literalExpression);
        if (methodName == null || BatchQueryMethodSupport.findBatchMethod(context, methodName) != null) {
            return;
        }

        TextRange valueRange = ElementManipulators
                .getValueTextRange(literalExpression)
                .shiftRight(literalExpression.getTextRange().getStartOffset());
        holder.newAnnotation(HighlightSeverity.ERROR, "Cannot resolve batch query method '" + methodName + "'")
                .range(valueRange)
                .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .create();
    }
}
