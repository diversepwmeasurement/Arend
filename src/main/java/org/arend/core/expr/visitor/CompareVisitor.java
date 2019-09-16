package org.arend.core.expr.visitor;

import org.arend.core.context.binding.Binding;
import org.arend.core.context.binding.TypedBinding;
import org.arend.core.context.binding.inference.InferenceVariable;
import org.arend.core.context.binding.inference.TypeClassInferenceVariable;
import org.arend.core.context.param.*;
import org.arend.core.definition.ClassField;
import org.arend.core.definition.Constructor;
import org.arend.core.definition.DataDefinition;
import org.arend.core.definition.Definition;
import org.arend.core.elimtree.BranchElimTree;
import org.arend.core.elimtree.ElimTree;
import org.arend.core.elimtree.LeafElimTree;
import org.arend.core.expr.*;
import org.arend.core.expr.type.ExpectedType;
import org.arend.core.expr.type.Type;
import org.arend.core.expr.type.TypeExpression;
import org.arend.core.sort.Sort;
import org.arend.core.subst.ExprSubstitution;
import org.arend.prelude.Prelude;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.error.local.GoalError;
import org.arend.typechecking.implicitargs.equations.DummyEquations;
import org.arend.typechecking.implicitargs.equations.Equations;
import org.arend.util.Pair;

import java.util.*;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class CompareVisitor extends BaseExpressionVisitor<Pair<Expression,ExpectedType>, Boolean> {
  private final Map<Binding, Binding> mySubstitution;
  private final Equations myEquations;
  private final Concrete.SourceNode mySourceNode;
  private Equations.CMP myCMP;
  private boolean myNormalCompare = true;

  public CompareVisitor(Equations equations, Equations.CMP cmp, Concrete.SourceNode sourceNode) {
    mySubstitution = new HashMap<>();
    myEquations = equations;
    mySourceNode = sourceNode;
    myCMP = cmp;
  }

  public static boolean compare(Equations equations, Equations.CMP cmp, Expression expr1, Expression expr2, ExpectedType type, Concrete.SourceNode sourceNode) {
    return new CompareVisitor(equations, cmp, sourceNode).compare(expr1, expr2, type);
  }

  // Only for tests
  public static boolean compare(Equations equations, ElimTree elimTree1, ElimTree elimTree2, Concrete.SourceNode sourceNode) {
    return new CompareVisitor(equations, Equations.CMP.EQ, sourceNode).compare(elimTree1, elimTree2, new ExprSubstitution(), EmptyDependentLink.getInstance(), null);
  }

  private Boolean compare(ElimTree elimTree1, ElimTree elimTree2, ExprSubstitution substitution, DependentLink parameters, Expression type) {
    if (elimTree1 == elimTree2) {
      return true;
    }
    if (!compareParameters(elimTree1.getParameters(), elimTree2.getParameters())) {
      return false;
    }

    for (DependentLink link = elimTree1.getParameters(); link.hasNext() && parameters.hasNext(); link = link.getNext(), parameters = parameters.getNext()) {
      substitution.add(parameters, new ReferenceExpression(link));
    }

    boolean ok = false;
    if (elimTree1 instanceof LeafElimTree && elimTree2 instanceof LeafElimTree) {
      ok = compare(((LeafElimTree) elimTree1).getExpression(), ((LeafElimTree) elimTree2).getExpression(), type == null ? null : type.subst(substitution));
    } else if (elimTree1 instanceof BranchElimTree && elimTree2 instanceof BranchElimTree) {
      BranchElimTree branchElimTree1 = (BranchElimTree) elimTree1;
      BranchElimTree branchElimTree2 = (BranchElimTree) elimTree2;
      if (branchElimTree1.getChildren().size() == branchElimTree2.getChildren().size()) {
        ok = true;
        for (Map.Entry<Constructor, ElimTree> entry : branchElimTree1.getChildren()) {
          ElimTree elimTree = branchElimTree2.getChild(entry.getKey());
          if (elimTree == null) {
            ok = false;
            break;
          }
          ok = compare(entry.getValue(), elimTree, substitution, parameters, null); // TODO: Compute new parameters correctly
          if (!ok) {
            break;
          }
        }
      }
    }

    for (DependentLink link = elimTree2.getParameters(); link.hasNext(); link = link.getNext()) {
      mySubstitution.remove(link);
    }
    return ok;
  }

  public boolean nonNormalizingCompare(Expression expr1, Expression expr2, ExpectedType type) {
    // Optimization for let clause calls
    if (expr1.isInstance(ReferenceExpression.class) && expr2.isInstance(ReferenceExpression.class) && expr1.cast(ReferenceExpression.class).getBinding() == expr2.cast(ReferenceExpression.class).getBinding()) {
      return true;
    }

    // Another optimization
    boolean check;
    if (expr1.isInstance(FunCallExpression.class)) {
      FunCallExpression funCall2 = expr2.checkedCast(FunCallExpression.class);
      check = funCall2 != null && expr1.cast(FunCallExpression.class).getDefinition() == funCall2.getDefinition() && !funCall2.getDefinition().isLemma();
    } else if (expr1.isInstance(AppExpression.class)) {
      check = expr2.isInstance(AppExpression.class);
    } else if (expr1.isInstance(FieldCallExpression.class)) {
      FieldCallExpression fieldCall2 = expr2.checkedCast(FieldCallExpression.class);
      check = fieldCall2 != null && expr1.cast(FieldCallExpression.class).getDefinition() == fieldCall2.getDefinition() && !fieldCall2.getDefinition().isProperty();
    } else if (expr1.isInstance(ProjExpression.class)) {
      ProjExpression proj2 = expr2.checkedCast(ProjExpression.class);
      check = proj2 != null && expr1.cast(ProjExpression.class).getField() == proj2.getField();
    } else {
      check = false;
    }

    if (check) {
      Equations.CMP origCMP = myCMP;
      myCMP = Equations.CMP.EQ;
      boolean normalCompare = myNormalCompare;
      myNormalCompare = false;

      boolean ok = expr1.accept(this, new Pair<>(expr2, type));

      myNormalCompare = normalCompare;
      myCMP = origCMP;
      return ok;
    }

    return false;
  }

  public boolean normalizedCompare(Expression expr1, Expression expr2, ExpectedType type) {
    Expression stuck1 = expr1.getCanonicalStuckExpression();
    Expression stuck2 = expr2.getCanonicalStuckExpression();
    if (stuck1 != null && stuck1.isError() && (stuck2 == null || !stuck2.isInstance(InferenceReferenceExpression.class)) ||
      stuck2 != null && stuck2.isError() && (stuck1 == null || !stuck1.isInstance(InferenceReferenceExpression.class))) {
      return true;
    }

    InferenceVariable stuckVar1 = expr1.getInferenceVariable();
    InferenceVariable stuckVar2 = expr2.getInferenceVariable();
    if (stuckVar1 != null || stuckVar2 != null) {
      return myNormalCompare && myEquations.addEquation(expr1, expr2.subst(getSubstitution()), type, myCMP, stuckVar1 != null ? stuckVar1.getSourceNode() : stuckVar2.getSourceNode(), stuckVar1, stuckVar2);
    }

    Equations.CMP origCMP = myCMP;
    Boolean dataAndApp = checkDefCallAndApp(expr1, expr2, true);
    if (dataAndApp != null) {
      return dataAndApp;
    }
    dataAndApp = checkDefCallAndApp(expr2, expr1, false);
    if (dataAndApp != null) {
      return dataAndApp;
    }

    if (!expr1.isInstance(UniverseExpression.class) && !expr1.isInstance(PiExpression.class) && !expr1.isInstance(ClassCallExpression.class) && !expr1.isInstance(DataCallExpression.class) && !expr1.isInstance(AppExpression.class) && !expr1.isInstance(SigmaExpression.class) && !expr1.isInstance(LamExpression.class)) {
      myCMP = Equations.CMP.EQ;
    }

    boolean ok;
    if (expr2.isInstance(ConCallExpression.class) && expr2.cast(ConCallExpression.class).getDefinition() == Prelude.PATH_CON) {
      ok = visitDefCall(expr2.cast(ConCallExpression.class), expr1, false);
    } else
    if (expr2.isInstance(LamExpression.class)) {
      ok = visitLam(expr2.cast(LamExpression.class), expr1, type, false);
    } else
    if (expr2.isInstance(TupleExpression.class)) {
      ok = visitTuple(expr2.cast(TupleExpression.class), expr1, false);
    } else {
      Expression normType = type instanceof Expression ? (myNormalCompare ? ((Expression) type).normalize(NormalizeVisitor.Mode.WHNF) : (Expression) type) : null;
      // TODO: Replace with the check that the sort of normType is \Prop
      if (normType != null && (normType.isInstance(SigmaExpression.class) && !normType.cast(SigmaExpression.class).getParameters().hasNext() || normType.isInstance(ClassCallExpression.class) && normType.cast(ClassCallExpression.class).isUnit())) {
        ok = true;
      } else {
        ok = expr1.accept(this, new Pair<>(expr2, type));
      }
    }
    if (ok) {
      return true;
    }

    InferenceVariable variable1 = stuck1 == null ? null : stuck1.getInferenceVariable();
    InferenceVariable variable2 = stuck2 == null ? null : stuck2.getInferenceVariable();
    return (variable1 != null || variable2 != null) && myNormalCompare && myEquations.addEquation(expr1, expr2.subst(getSubstitution()), type, origCMP, variable1 != null ? variable1.getSourceNode() : variable2.getSourceNode(), variable1, variable2);
  }

  public Boolean compare(Expression expr1, Expression expr2, ExpectedType type) {
    expr1 = expr1.getCanonicalExpression();
    expr2 = expr2.getCanonicalExpression();
    if (expr1 == expr2) {
      return true;
    }

    InferenceReferenceExpression infRefExpr1 = expr1.checkedCast(InferenceReferenceExpression.class);
    InferenceReferenceExpression infRefExpr2 = expr2.checkedCast(InferenceReferenceExpression.class);
    if (infRefExpr1 != null && infRefExpr2 != null && infRefExpr1.getVariable() == infRefExpr2.getVariable()) {
      return true;
    }
    if (infRefExpr1 != null) {
      return myNormalCompare && myEquations.addEquation(infRefExpr1, expr2.subst(getSubstitution()).normalize(NormalizeVisitor.Mode.WHNF), type, myCMP, infRefExpr1.getVariable().getSourceNode(), infRefExpr1.getVariable(), expr2.getStuckInferenceVariable());
    }
    if (infRefExpr2 != null) {
      return myNormalCompare && myEquations.addEquation(expr1.normalize(NormalizeVisitor.Mode.WHNF), infRefExpr2, type, myCMP, infRefExpr2.getVariable().getSourceNode(), expr1.getStuckInferenceVariable(), infRefExpr2.getVariable());
    }

    InferenceVariable stuckVar1 = expr1.getStuckInferenceVariable();
    InferenceVariable stuckVar2 = expr2.getStuckInferenceVariable();
    if (stuckVar1 != stuckVar2 && (!myNormalCompare || myEquations == DummyEquations.getInstance())) {
      return false;
    }
    if (stuckVar1 == stuckVar2 && nonNormalizingCompare(expr1, expr2, type)) {
      return true;
    }

    return myNormalCompare && normalizedCompare(expr1.normalize(NormalizeVisitor.Mode.WHNF), expr2.normalize(NormalizeVisitor.Mode.WHNF), type);
  }

  private ExprSubstitution getSubstitution() {
    ExprSubstitution substitution = new ExprSubstitution();
    for (Map.Entry<Binding, Binding> entry : mySubstitution.entrySet()) {
      substitution.add(entry.getKey(), new ReferenceExpression(entry.getValue()));
    }
    return substitution;
  }

  @Override
  public Boolean visitApp(AppExpression expr1, Pair<Expression,ExpectedType> pair) {
    List<Expression> args1 = new ArrayList<>();
    Expression fun1 = expr1;
    while (fun1.isInstance(AppExpression.class)) {
      args1.add(fun1.cast(AppExpression.class).getArgument());
      fun1 = fun1.cast(AppExpression.class).getFunction();
    }

    List<Expression> args2 = new ArrayList<>();
    Expression fun2 = pair.proj1;
    while (fun2.isInstance(AppExpression.class)) {
      args2.add(fun2.cast(AppExpression.class).getArgument());
      fun2 = fun2.cast(AppExpression.class).getFunction();
    }

    InferenceVariable var1 = fun1.getInferenceVariable();
    InferenceVariable var2 = fun2.getInferenceVariable();
    if (var1 != null || var2 != null) {
      if (myNormalCompare && myEquations.addEquation(expr1, pair.proj1.subst(getSubstitution()), pair.proj2, myCMP, var1 != null ? var1.getSourceNode() : var2.getSourceNode(), var1, var2)) {
        return true;
      }
    }

    if (args1.size() != args2.size()) {
      return false;
    }

    if (!compare(fun1, fun2, null)) {
      return false;
    }
    if (args1.isEmpty()) {
      return true;
    }

    Collections.reverse(args1);
    Collections.reverse(args2);

    myCMP = Equations.CMP.EQ;
    Expression type1 = fun1.getType();
    List<SingleDependentLink> params = Collections.emptyList();
    if (type1 != null) {
      params = new ArrayList<>();
      type1.getPiParameters(params, false);
    }

    for (int i = 0; i < args1.size(); i++) {
      if (!compare(args1.get(i), args2.get(i), i < params.size() ? params.get(i).getTypeExpr() : null)) {
        return false;
      }
    }

    return true;
  }

  private Boolean comparePathEta(ConCallExpression conCall1, Expression expr2, boolean correctOrder) {
    SingleDependentLink param = new TypedSingleDependentLink(true, "i", ExpressionFactory.Interval());
    ReferenceExpression paramRef = new ReferenceExpression(param);
    List<Expression> args = new ArrayList<>(5);
    for (Expression arg : conCall1.getDataTypeArguments()) {
      args.add(correctOrder ? arg : arg.subst(getSubstitution()));
    }
    args.add(expr2);
    args.add(paramRef);
    expr2 = new LamExpression(conCall1.getSortArgument(), param, new FunCallExpression(Prelude.AT, conCall1.getSortArgument(), args));
    Expression type = new PiExpression(conCall1.getSortArgument(), param, AppExpression.make(conCall1.getDataTypeArguments().get(0), paramRef));
    return correctOrder ? compare(conCall1.getDefCallArguments().get(0), expr2, type) : compare(expr2, conCall1.getDefCallArguments().get(0), type);
  }

  private boolean compareDef(DefCallExpression expr1, DefCallExpression expr2, boolean correctOrder) {
    if (expr2 == null || expr1.getDefinition() != expr2.getDefinition()) {
      return false;
    }
    if (!expr1.hasUniverses()) {
      return true;
    }
    return correctOrder
      ? Sort.compare(expr1.getSortArgument(), expr2.getSortArgument(), myCMP, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode)
      : Sort.compare(expr2.getSortArgument(), expr1.getSortArgument(), myCMP, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode);
  }

  private Boolean visitDefCall(DefCallExpression expr1, Expression expr2, boolean correctOrder) {
    if (expr1.getDefinition() == Prelude.PATH_CON && !expr2.isInstance(ConCallExpression.class)) {
      return comparePathEta((ConCallExpression) expr1, expr2, correctOrder);
    }

    DefCallExpression defCall2 = expr2.checkedCast(DefCallExpression.class);
    return
      compareDef(expr1, defCall2, correctOrder) &&
      (correctOrder ? compareLists(expr1.getDefCallArguments(), defCall2.getDefCallArguments(), expr1.getDefinition().getParameters(), expr1.getDefinition()) : compareLists(defCall2.getDefCallArguments(), expr1.getDefCallArguments(), defCall2.getDefinition().getParameters(), defCall2.getDefinition()));
  }

  @Override
  public Boolean visitDefCall(DefCallExpression expr1, Pair<Expression,ExpectedType> pair) {
    if (expr1 instanceof ConCallExpression && pair.proj1.isInstance(IntegerExpression.class)) {
      return visitInteger(pair.proj1.cast(IntegerExpression.class), expr1);
    }
    return visitDefCall(expr1, pair.proj1, true);
  }

  private Boolean checkDefCallAndApp(Expression expr1, Expression expr2, boolean correctOrder) {
    DataCallExpression dataCall1 = expr1.checkedCast(DataCallExpression.class);
    ClassCallExpression classCall1 = dataCall1 == null ? expr1.checkedCast(ClassCallExpression.class) : null;
    if (dataCall1 == null && classCall1 == null) {
      return null;
    }
    AppExpression app2 = expr2.checkedCast(AppExpression.class);
    if (app2 == null) {
      return null;
    }

    List<Expression> args = new ArrayList<>();
    while (true) {
      args.add(app2.getArgument());
      Expression fun = app2.getFunction();
      if (fun.isInstance(AppExpression.class)) {
        app2 = fun.cast(AppExpression.class);
        continue;
      }

      TypeClassInferenceVariable variable;
      if (fun.isInstance(FieldCallExpression.class)) {
        FieldCallExpression fieldCall = fun.cast(FieldCallExpression.class);
        InferenceVariable infVar = fieldCall.getArgument().getInferenceVariable();
        variable = infVar instanceof TypeClassInferenceVariable ? (TypeClassInferenceVariable) infVar : null;
      } else {
        variable = null;
      }
      if (variable == null || dataCall1 != null && args.size() > dataCall1.getDefCallArguments().size() || classCall1 != null && args.size() > classCall1.getDefinition().getNumberOfNotImplementedFields()) {
        return null;
      }
      Collections.reverse(args);

      DependentLink dataParams;
      List<Expression> oldDataArgs;
      if (dataCall1 != null) {
        dataParams = dataCall1.getDefinition().getParameters();
        oldDataArgs = dataCall1.getDefCallArguments();
      } else {
        oldDataArgs = new ArrayList<>();
        for (ClassField field : classCall1.getDefinition().getFields()) {
          if (!field.getReferable().isParameterField()) {
            break;
          }
          Expression implementation = classCall1.getImplementationHere(field);
          if (implementation != null) {
            oldDataArgs.add(implementation);
          } else {
            if (!classCall1.getDefinition().isImplemented(field)) {
              break;
            }
          }
        }
        if (args.size() > oldDataArgs.size() || classCall1.getImplementedHere().size() > oldDataArgs.size() && !(correctOrder && myCMP == Equations.CMP.LE || !correctOrder && myCMP == Equations.CMP.GE)) {
          return null;
        }
        dataParams = classCall1.getClassFieldParameters();
      }

      List<Expression> oldList = oldDataArgs.subList(oldDataArgs.size() - args.size(), oldDataArgs.size());
      if (!compareLists(correctOrder ? oldList : args, correctOrder ? args : oldList, dataParams, dataCall1 == null ? null : dataCall1.getDefinition())) {
        return false;
      }

      Expression lam;
      Sort codSort;
      List<SingleDependentLink> params = new ArrayList<>();
      if (dataCall1 != null) {
        int numberOfOldArgs = oldDataArgs.size() - args.size();
        for (int i = 0; i < numberOfOldArgs; i++) {
          dataParams = dataParams.getNext();
        }
        List<Expression> newDataArgs = new ArrayList<>(oldDataArgs.subList(0, numberOfOldArgs));
        lam = new DataCallExpression(dataCall1.getDefinition(), dataCall1.getSortArgument(), newDataArgs);
        codSort = dataCall1.getDefinition().getSort();

        SingleDependentLink firstParam = null;
        SingleDependentLink lastParam = null;
        for (; dataParams.hasNext(); dataParams = dataParams.getNext()) {
          SingleDependentLink link;
          if (dataParams instanceof TypedDependentLink) {
            link = new TypedSingleDependentLink(dataParams.isExplicit(), dataParams.getName(), dataParams.getType());
          } else {
            link = new UntypedSingleDependentLink(dataParams.getName());
          }
          newDataArgs.add(new ReferenceExpression(link));
          if (firstParam == null) {
            firstParam = link;
          }
          if (lastParam == null) {
            lastParam = link;
          } else {
            lastParam.setNext(link);
          }
          if (link instanceof TypedSingleDependentLink) {
            params.add(firstParam);
            firstParam = null;
            lastParam = null;
          }
        }
      } else {
        Map<ClassField, Expression> implementations = new HashMap<>();
        codSort = classCall1.getSort();
        ClassCallExpression classCall = new ClassCallExpression(classCall1.getDefinition(), classCall1.getSortArgument(), implementations, codSort, classCall1.hasUniverses());
        int i = 0;
        for (ClassField field : classCall1.getDefinition().getFields()) {
          if (!classCall1.getDefinition().isImplemented(field)) {
            if (i < oldDataArgs.size() - args.size()) {
              implementations.put(field, classCall1.getImplementationHere(field));
              i++;
            } else {
              PiExpression piType = field.getType(classCall1.getSortArgument());
              Expression type = piType.getCodomain();
              TypedSingleDependentLink link = new TypedSingleDependentLink(field.getReferable().isExplicitField(), field.getName(), type instanceof Type ? (Type) type : new TypeExpression(type, piType.getResultSort()));
              params.add(link);
              implementations.put(field, new ReferenceExpression(link));
            }
            if (implementations.size() == oldDataArgs.size()) {
              break;
            }
          }
        }
        classCall.updateHasUniverses();
        lam = classCall;
      }

      for (int i = params.size() - 1; i >= 0; i--) {
        if (!myNormalCompare || myEquations.isDummy()) {
          return false;
        }
        codSort = PiExpression.generateUpperBound(params.get(i).getType().getSortOfType(), codSort, myEquations, mySourceNode);
        lam = new LamExpression(codSort, params.get(i), lam);
      }

      Expression finalExpr1 = correctOrder ? lam : fun;
      Expression finalExpr2 = correctOrder ? fun : lam.subst(getSubstitution());
      if (variable.isSolved()) {
        return compare(myNormalCompare ? myEquations : DummyEquations.getInstance(), myCMP, finalExpr1, finalExpr2, null, variable.getSourceNode());
      } else {
        return myNormalCompare && myEquations.addEquation(finalExpr1, finalExpr2, null, myCMP, variable.getSourceNode(), correctOrder ? null : variable, correctOrder ? variable : null) ? true : null;
      }
    }
  }

  @Override
  public Boolean visitDataCall(DataCallExpression dataCall1, Pair<Expression,ExpectedType> pair) {
    DataCallExpression dataCall2 = pair.proj1.checkedCast(DataCallExpression.class);
    if (!compareDef(dataCall1, dataCall2, true)) {
      return false;
    }

    Equations.CMP origCMP = myCMP;
    DependentLink link = dataCall1.getDefinition().getParameters();
    ExprSubstitution substitution = new ExprSubstitution();
    for (int i = 0; i < dataCall1.getDefCallArguments().size(); i++) {
      myCMP = dataCall1.getDefinition().isCovariant(i) ? origCMP : Equations.CMP.EQ;
      if (!compare(dataCall1.getDefCallArguments().get(i), dataCall2.getDefCallArguments().get(i), link.hasNext() ? link.getTypeExpr().subst(substitution) : null)) {
        return false;
      }
      if (link.hasNext()) {
        substitution.add(link, dataCall1.getDefCallArguments().get(i));
        link = link.getNext();
      }
    }
    return true;
  }

  @Override
  public Boolean visitFieldCall(FieldCallExpression fieldCall1, Pair<Expression,ExpectedType> pair) {
    FieldCallExpression fieldCall2 = pair.proj1.checkedCast(FieldCallExpression.class);
    if (fieldCall2 == null || fieldCall1.getDefinition() != fieldCall2.getDefinition()) {
      return false;
    }

    InferenceVariable var1 = fieldCall1.getInferenceVariable();
    InferenceVariable var2 = fieldCall2.getInferenceVariable();
    if (var1 != null || var2 != null) {
      return myNormalCompare && myEquations.addEquation(fieldCall1, fieldCall2.subst(getSubstitution()), pair.proj2, Equations.CMP.EQ, var1 != null ? var1.getSourceNode() : var2.getSourceNode(), var1, var2);
    }

    return compare(fieldCall1.getArgument(), fieldCall2.getArgument(), null);
  }

  private boolean checkSubclassImpl(ClassCallExpression classCall1, ClassCallExpression classCall2, boolean correctOrder) {
    Equations.CMP origCMP = myCMP;
    for (Map.Entry<ClassField, Expression> entry : classCall2.getImplementedHere().entrySet()) {
      if (entry.getKey().isProperty()) {
        continue;
      }

      Expression impl1 = classCall1.getImplementationHere(entry.getKey());
      Binding binding = null;
      if (impl1 == null) {
        LamExpression lamImpl1 = classCall1.getDefinition().getImplementation(entry.getKey());
        if (lamImpl1 != null) {
          impl1 = lamImpl1.getBody();
          binding = lamImpl1.getParameters();
        }
      }
      if (impl1 == null) {
        return false;
      }
      if (binding == null) {
        binding = new TypedBinding("this", classCall2);
      }
      if (!entry.getKey().isCovariant()) {
        myCMP = Equations.CMP.EQ;
      }
      if (!compare(correctOrder ? impl1 : entry.getValue(), correctOrder ? entry.getValue() : impl1, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(new ReferenceExpression(binding)))) {
        return false;
      }
      myCMP = origCMP;
    }
    return true;
  }

  private boolean checkClassCallSortArguments(ClassCallExpression classCall1, ClassCallExpression classCall2) {
    ReferenceExpression thisExpr = new ReferenceExpression(new TypedBinding("this", new ClassCallExpression(classCall1.getDefinition(), classCall2.getSortArgument(), classCall1.getImplementedHere(), classCall1.getSort(), classCall1.hasUniverses())));
    boolean ok = true;
    for (Map.Entry<ClassField, LamExpression> entry : classCall1.getDefinition().getImplemented()) {
      if (entry.getKey().hasUniverses() && !classCall2.isImplemented(entry.getKey())) {
        Expression type = entry.getValue().substArgument(thisExpr).getType();
        if (type == null) {
          ok = false;
          break;
        }
        if (!compare(myNormalCompare ? myEquations : DummyEquations.getInstance(), Equations.CMP.LE, type, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(thisExpr), ExpectedType.OMEGA, mySourceNode)) {
          return false;
        }
      }
    }
    if (ok) {
      for (Map.Entry<ClassField, Expression> entry : classCall1.getImplementedHere().entrySet()) {
        if (entry.getKey().hasUniverses() && !classCall2.isImplemented(entry.getKey())) {
          Expression type = entry.getValue().getType();
          if (type == null) {
            ok = false;
            break;
          }
          if (!compare(myNormalCompare ? myEquations : DummyEquations.getInstance(), Equations.CMP.LE, type, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(thisExpr), ExpectedType.OMEGA, mySourceNode)) {
            return false;
          }
        }
      }
    }

    return ok || Sort.compare(classCall1.getSortArgument(), classCall2.getSortArgument(), myCMP, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode);
  }

  @Override
  public Boolean visitClassCall(ClassCallExpression expr1, Pair<Expression,ExpectedType> pair) {
    ClassCallExpression classCall2 = pair.proj1.checkedCast(ClassCallExpression.class);
    if (classCall2 == null) {
      return false;
    }

    if (expr1.hasUniverses() || classCall2.hasUniverses()) {
      if (myCMP == Equations.CMP.EQ || expr1.hasUniverses() && classCall2.hasUniverses()) {
        if (!Sort.compare(expr1.getSortArgument(), classCall2.getSortArgument(), myCMP, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode)) {
          return false;
        }
      } else {
        if (!Sort.compare(expr1.getSortArgument(), classCall2.getSortArgument(), myCMP, DummyEquations.getInstance(), mySourceNode)) {
          if (myCMP == Equations.CMP.LE ? !checkClassCallSortArguments(expr1, classCall2) : !checkClassCallSortArguments(classCall2, expr1)) {
            return false;
          }
        }
      }
    }

    if (myCMP == Equations.CMP.LE) {
      if (!expr1.getDefinition().isSubClassOf(classCall2.getDefinition())) {
        return false;
      }
      return checkSubclassImpl(expr1, classCall2, true);
    }

    if (myCMP == Equations.CMP.GE) {
      if (!classCall2.getDefinition().isSubClassOf(expr1.getDefinition())) {
        return false;
      }
      return checkSubclassImpl(classCall2, expr1, false);
    }

    return expr1.getDefinition() == classCall2.getDefinition() && expr1.getImplementedHere().size() == classCall2.getImplementedHere().size() && checkSubclassImpl(expr1, classCall2, true);
  }

  @Override
  public Boolean visitReference(ReferenceExpression expr1, Pair<Expression,ExpectedType> pair) {
    ReferenceExpression ref2 = pair.proj1.checkedCast(ReferenceExpression.class);
    if (ref2 == null) {
      return false;
    }

    Binding binding2 = ref2.getBinding();
    Binding subst2 = mySubstitution.get(binding2);
    if (subst2 != null) {
      binding2 = subst2;
    }
    return binding2 == expr1.getBinding();
  }

  @Override
  public Boolean visitInferenceReference(InferenceReferenceExpression expr1, Pair<Expression,ExpectedType> pair) {
    if (expr1.getSubstExpression() == null) {
      return pair.proj1.isInstance(InferenceReferenceExpression.class) && pair.proj1.cast(InferenceReferenceExpression.class).getVariable() == expr1.getVariable();
    } else {
      return expr1.getSubstExpression().accept(this, pair);
    }
  }

  private Boolean visitLam(LamExpression expr1, Expression expr2, ExpectedType type, boolean correctOrder) {
    List<DependentLink> params1 = new ArrayList<>();
    List<DependentLink> params2 = new ArrayList<>();
    Expression body1 = expr1.getLamParameters(params1);
    Expression body2 = expr2.getLamParameters(params2);

    for (int i = 0; i < params1.size() && i < params2.size(); i++) {
      mySubstitution.put(correctOrder ? params2.get(i) : params1.get(i), correctOrder ? params1.get(i) : params2.get(i));
    }

    if (params1.size() < params2.size()) {
      for (int i = params1.size(); i < params2.size(); i++) {
        body1 = AppExpression.make(body1, new ReferenceExpression(params2.get(i)));
      }
    }
    if (params2.size() < params1.size()) {
      for (int i = params2.size(); i < params1.size(); i++) {
        body2 = AppExpression.make(body2, new ReferenceExpression(params1.get(i)));
      }
    }

    if (type instanceof Expression) {
      type = ((Expression) type).dropPiParameter(Math.max(params1.size(), params2.size()));
    }
    Boolean result = compare(correctOrder ? body1 : body2, correctOrder ? body2 : body1, type);
    for (int i = 0; i < params1.size() && i < params2.size(); i++) {
      mySubstitution.remove(correctOrder ? params2.get(i) : params1.get(i));
    }
    return result;
  }

  @Override
  public Boolean visitLam(LamExpression expr1, Pair<Expression,ExpectedType> pair) {
    return visitLam(expr1, pair.proj1, pair.proj2, true);
  }

  @Override
  public Boolean visitPi(PiExpression expr1, Pair<Expression,ExpectedType> pair) {
    if (!pair.proj1.isInstance(PiExpression.class)) {
      return false;
    }
    PiExpression piExpr2 = pair.proj1.cast(PiExpression.class);

    Equations.CMP origCMP = myCMP;
    myCMP = Equations.CMP.EQ;
    if (!compare(expr1.getParameters().getTypeExpr(), piExpr2.getParameters().getTypeExpr(), ExpectedType.OMEGA)) {
      return false;
    }

    SingleDependentLink link1 = expr1.getParameters(), link2 = piExpr2.getParameters();
    for (; link1.hasNext() && link2.hasNext(); link1 = link1.getNext(), link2 = link2.getNext()) {
      mySubstitution.put(link2, link1);
    }

    myCMP = origCMP;
    if (!compare(link1.hasNext() ? new PiExpression(expr1.getResultSort(), link1, expr1.getCodomain()) : expr1.getCodomain(), link2.hasNext() ? new PiExpression(piExpr2.getResultSort(), link2, piExpr2.getCodomain()) : piExpr2.getCodomain(), ExpectedType.OMEGA)) {
      return false;
    }

    for (DependentLink link = piExpr2.getParameters(); link != link2; link = link.getNext()) {
      mySubstitution.remove(link);
    }
    mySubstitution.remove(link2);
    return true;
  }

  public boolean compareParameters(DependentLink params1, DependentLink params2) {
    List<DependentLink> list1 = DependentLink.Helper.toList(params1);
    List<DependentLink> list2 = DependentLink.Helper.toList(params2);

    if (list1.size() != list2.size()) {
      return false;
    }

    Equations.CMP origCMP = myCMP;
    for (int i = 0; i < list1.size() && i < list2.size(); ++i) {
      if (!compare(list1.get(i).getTypeExpr(), list2.get(i).getTypeExpr(), ExpectedType.OMEGA)) {
        return false;
      }
      mySubstitution.put(list2.get(i), list1.get(i));
      myCMP = origCMP;
    }

    return true;
  }


  @Override
  public Boolean visitUniverse(UniverseExpression expr1, Pair<Expression,ExpectedType> pair) {
    return pair.proj1.isInstance(UniverseExpression.class) && Sort.compare(expr1.getSort(), pair.proj1.cast(UniverseExpression.class).getSort(), myCMP, myNormalCompare ? (myEquations == DummyEquations.getInstance() ? null : myEquations) : DummyEquations.getInstance(), mySourceNode);
  }

  @Override
  public Boolean visitError(ErrorExpression expr1, Pair<Expression,ExpectedType> pair) {
    return expr1.getError() instanceof GoalError && pair.proj1.isInstance(ErrorExpression.class) && expr1.getError().equals(pair.proj1.cast(ErrorExpression.class).getError());
  }

  @Override
  public Boolean visitTuple(TupleExpression expr1, Pair<Expression,ExpectedType> pair) {
    return visitTuple(expr1, pair.proj1, true);
  }

  private Boolean visitTuple(TupleExpression expr1, Expression expr2, boolean correctOrder) {
    Expression type2 = expr2.getType();
    if (type2 == null || !compare(correctOrder ? expr1.getSigmaType() : type2, correctOrder ? type2 : expr1.getSigmaType(), ExpectedType.OMEGA)) {
      return false;
    }

    if (expr2.isInstance(TupleExpression.class)) {
      TupleExpression tuple2 = expr2.cast(TupleExpression.class);
      return correctOrder ? compareLists(expr1.getFields(), tuple2.getFields(), expr1.getSigmaType().getParameters(), null) : compareLists(tuple2.getFields(), expr1.getFields(), tuple2.getSigmaType().getParameters(), null);
    } else {
      List<Expression> args2 = new ArrayList<>(expr1.getFields().size());
      for (int i = 0; i < expr1.getFields().size(); i++) {
        args2.add(ProjExpression.make(expr2, i));
      }
      return correctOrder ? compareLists(expr1.getFields(), args2, expr1.getSigmaType().getParameters(), null) : compareLists(args2, expr1.getFields(), expr1.getSigmaType().getParameters(), null);
    }
  }

  @Override
  public Boolean visitSigma(SigmaExpression expr1, Pair<Expression,ExpectedType> pair) {
    SigmaExpression sigma2 = pair.proj1.checkedCast(SigmaExpression.class);
    if (sigma2 == null) {
      return false;
    }
    if (!compareParameters(expr1.getParameters(), sigma2.getParameters())) {
      return false;
    }
    for (DependentLink link = sigma2.getParameters(); link.hasNext(); link = link.getNext()) {
      mySubstitution.remove(link);
    }
    return true;
  }

  @Override
  public Boolean visitProj(ProjExpression expr1, Pair<Expression,ExpectedType> pair) {
    if (!pair.proj1.isInstance(ProjExpression.class)) {
      return false;
    }
    ProjExpression proj2 = pair.proj1.cast(ProjExpression.class);
    return expr1.getField() == proj2.getField() && compare(expr1.getExpression(), proj2.getExpression(), null);
  }

  @Override
  public Boolean visitNew(NewExpression expr1, Pair<Expression,ExpectedType> pair) {
    return false;
  }

  @Override
  public Boolean visitLet(LetExpression letExpr1, Pair<Expression,ExpectedType> pair) {
    throw new IllegalStateException();
  }

  private boolean compareLists(List<? extends Expression> list1, List<? extends Expression> list2, DependentLink link, Definition definition) {
    if (list1.size() != list2.size()) {
      return false;
    }

    Equations.CMP origCMP = myCMP;
    ExprSubstitution substitution = new ExprSubstitution();
    for (int i = 0; i < list1.size(); i++) {
      if (definition instanceof DataDefinition) {
        myCMP = ((DataDefinition) definition).isCovariant(i) ? origCMP : Equations.CMP.EQ;
      }
      if (!compare(list1.get(i), list2.get(i), link.hasNext() ? link.getTypeExpr().subst(substitution) : null)) {
        myCMP = origCMP;
        return false;
      }
      if (link.hasNext()) {
        substitution.add(link, list1.get(i));
        link = link.getNext();
      }
    }

    myCMP = origCMP;
    return true;
  }

  @Override
  public Boolean visitCase(CaseExpression case1, Pair<Expression,ExpectedType> pair) {
    if (!pair.proj1.isInstance(CaseExpression.class)) {
      return false;
    }
    CaseExpression case2 = pair.proj1.cast(CaseExpression.class);

    if (case1.getArguments().size() != case2.getArguments().size()) {
      return false;
    }

    if (!compareParameters(case1.getParameters(), case2.getParameters())) {
      return false;
    }

    if (!compare(case1.getResultType(), case2.getResultType(), ExpectedType.OMEGA)) {
      return false;
    }

    for (DependentLink link = case2.getParameters(); link.hasNext(); link = link.getNext()) {
      mySubstitution.remove(link);
    }

    if (!compareLists(case1.getArguments(), case2.getArguments(), case1.getParameters(), null)) {
      return false;
    }

    return compare(case1.getElimTree(), case2.getElimTree(), new ExprSubstitution(), case1.getParameters(), case1.getResultType());
  }

  @Override
  public Boolean visitOfType(OfTypeExpression expr, Pair<Expression,ExpectedType> pair) {
    return expr.getExpression().accept(this, pair);
  }

  private boolean visitInteger(IntegerExpression expr1, Expression expr2) {
    if (expr2.isInstance(IntegerExpression.class)) {
      return expr1.isEqual(expr2.cast(IntegerExpression.class));
    }

    ConCallExpression conCall2 = expr2.checkedCast(ConCallExpression.class);
    Constructor constructor2 = conCall2 == null ? null : conCall2.getDefinition();
    if (constructor2 == null || !expr1.match(constructor2)) {
      return false;
    }
    if (constructor2 == Prelude.ZERO) {
      return true;
    }
    return compare(expr1.pred(), conCall2.getDefCallArguments().get(0), ExpressionFactory.Nat());
  }

  @Override
  public Boolean visitInteger(IntegerExpression expr, Pair<Expression,ExpectedType> pair) {
    return visitInteger(expr, pair.proj1);
  }
}
