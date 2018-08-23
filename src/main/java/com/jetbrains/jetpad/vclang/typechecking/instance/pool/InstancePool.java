package com.jetbrains.jetpad.vclang.typechecking.instance.pool;

import com.jetbrains.jetpad.vclang.core.expr.Expression;
import com.jetbrains.jetpad.vclang.core.subst.ExprSubstitution;
import com.jetbrains.jetpad.vclang.naming.reference.TCClassReferable;
import com.jetbrains.jetpad.vclang.term.concrete.Concrete;
import com.jetbrains.jetpad.vclang.typechecking.implicitargs.equations.Equations;

public interface InstancePool {
  Expression getInstance(Expression classifyingExpression, TCClassReferable classRef, boolean isField, Equations equations, Concrete.SourceNode sourceNode);
  InstancePool subst(ExprSubstitution substitution);
}
