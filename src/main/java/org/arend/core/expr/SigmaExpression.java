package org.arend.core.expr;

import org.arend.core.context.param.DependentLink;
import org.arend.core.expr.type.Type;
import org.arend.core.expr.visitor.ExpressionVisitor;
import org.arend.core.expr.visitor.ExpressionVisitor2;
import org.arend.core.expr.visitor.NormalizeVisitor;
import org.arend.core.expr.visitor.StripVisitor;
import org.arend.core.sort.Sort;
import org.arend.core.subst.SubstVisitor;
import org.arend.error.ErrorReporter;
import org.arend.ext.core.expr.CoreExpressionVisitor;
import org.arend.ext.core.expr.CoreSigmaExpression;
import org.arend.util.Decision;

import javax.annotation.Nonnull;

public class SigmaExpression extends Expression implements Type, CoreSigmaExpression {
  private final DependentLink myLink;
  private final Sort mySort;

  public SigmaExpression(Sort sort, DependentLink link) {
    assert link != null;
    myLink = link;
    mySort = sort;
  }

  @Nonnull
  @Override
  public DependentLink getParameters() {
    return myLink;
  }

  public Sort getSort() {
    return mySort;
  }

  @Override
  public Expression getExpr() {
    return this;
  }

  @Override
  public Sort getSortOfType() {
    return mySort;
  }

  @Override
  public <P, R> R accept(ExpressionVisitor<? super P, ? extends R> visitor, P params) {
    return visitor.visitSigma(this, params);
  }

  @Override
  public <P1, P2, R> R accept(ExpressionVisitor2<? super P1, ? super P2, ? extends R> visitor, P1 param1, P2 param2) {
    return visitor.visitSigma(this, param1, param2);
  }

  @Override
  public <P, R> R accept(@Nonnull CoreExpressionVisitor<? super P, ? extends R> visitor, P params) {
    return visitor.visitSigma(this, params);
  }

  @Override
  public SigmaExpression subst(SubstVisitor substVisitor) {
    return substVisitor.isEmpty() ? this : (SigmaExpression) substVisitor.visitSigma(this, null);
  }

  @Override
  public SigmaExpression strip(ErrorReporter errorReporter) {
    return new StripVisitor(errorReporter).visitSigma(this, null);
  }

  @Override
  public SigmaExpression normalize(NormalizeVisitor.Mode mode) {
    return NormalizeVisitor.INSTANCE.visitSigma(this, mode);
  }

  @Override
  public Decision isWHNF() {
    return Decision.YES;
  }

  @Override
  public Expression getStuckExpression() {
    return null;
  }
}
