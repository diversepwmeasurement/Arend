package com.jetbrains.jetpad.vclang.frontend;

import com.jetbrains.jetpad.vclang.frontend.parser.Position;
import com.jetbrains.jetpad.vclang.naming.reference.GlobalReferable;
import com.jetbrains.jetpad.vclang.naming.reference.Referable;
import com.jetbrains.jetpad.vclang.term.Concrete;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckableProvider;

public class ConcreteTypecheckableProvider implements TypecheckableProvider<Position> {
  public static final ConcreteTypecheckableProvider INSTANCE = new ConcreteTypecheckableProvider();

  private ConcreteTypecheckableProvider() { }

  @Override
  public Concrete.Definition<Position> forReferable(GlobalReferable globalReferable) {
    Referable referable = globalReferable;
    if (referable instanceof Concrete.ClassViewField) {
      referable = ((Concrete.ClassViewField) referable).getUnderlyingField();
    }
    if (referable instanceof Concrete.ClassView) {
      referable = ((Concrete.ClassView) referable).getUnderlyingClass().getReferent();
    }
    return (Concrete.Definition<Position>) referable; // TODO[abstract]
  }
}
