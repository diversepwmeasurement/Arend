package com.jetbrains.jetpad.vclang.typechecking.implicitargs;

import com.jetbrains.jetpad.vclang.term.*;
import com.jetbrains.jetpad.vclang.term.context.binding.inference.FunctionInferenceVariable;
import com.jetbrains.jetpad.vclang.term.context.param.DependentLink;
import com.jetbrains.jetpad.vclang.term.expr.ConCallExpression;
import com.jetbrains.jetpad.vclang.term.expr.ErrorExpression;
import com.jetbrains.jetpad.vclang.term.expr.Expression;
import com.jetbrains.jetpad.vclang.term.expr.InferenceReferenceExpression;
import com.jetbrains.jetpad.vclang.term.expr.subst.ExprSubstitution;
import com.jetbrains.jetpad.vclang.term.expr.subst.LevelSubstitution;
import com.jetbrains.jetpad.vclang.term.expr.type.Type;
import com.jetbrains.jetpad.vclang.term.expr.visitor.CheckTypeVisitor;
import com.jetbrains.jetpad.vclang.term.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.typechecking.error.TypeCheckingError;
import com.jetbrains.jetpad.vclang.typechecking.error.TypeMismatchError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;

public class StdImplicitArgsInference extends BaseImplicitArgsInference {
  private Abstract.Definition myParentDefinition;

  public StdImplicitArgsInference(Abstract.Definition definition, CheckTypeVisitor visitor) {
    super(visitor);
    myParentDefinition = definition;
  }

  protected boolean fixImplicitArgs(CheckTypeVisitor.Result result, List<DependentLink> parameters, Abstract.Expression expr) {
    if (parameters.isEmpty()) {
      return true;
    }

    ExprSubstitution substitution = new ExprSubstitution();
    List<Expression> arguments = new ArrayList<>();
    for (int i = 0; i < parameters.size(); i++) {
      DependentLink parameter = parameters.get(i);
      Expression binding = new InferenceReferenceExpression(new FunctionInferenceVariable(parameter.getName(), parameter.getType().subst(substitution), i + 1, expr));
      arguments.add(binding);
      substitution.add(parameter, binding);
    }
    result.expression = Apps(result.expression, arguments);
    result.type = result.type.subst(substitution, new LevelSubstitution());
    return true;
  }

  protected CheckTypeVisitor.Result inferArg(CheckTypeVisitor.Result result, Abstract.Expression arg, boolean isExplicit, Abstract.Expression fun) {
    if (result == null) {
      return null;
    }

    if (isExplicit) {
      ConCallExpression conCall = result.expression.getFunction().toConCall();
      if (conCall != null && conCall.getDefinition() == Prelude.PATH_CON && result.expression.getArguments().isEmpty()) {
        Expression interval = DataCall(Prelude.INTERVAL);
        List<DependentLink> pathParams = new ArrayList<>();

        ((Expression) conCall.getType()).getPiParameters(pathParams, false, false);
        DependentLink lamParam = param("i", interval);
        Expression binding = new InferenceReferenceExpression(new FunctionInferenceVariable("A", pathParams.get(0).getType().toPi().getCodomain(), 1, fun));
        Expression lamExpr = Lam(lamParam, binding);
        result.expression = result.expression.addArgument(lamExpr);
        result.type = result.type.applyExpressions(Collections.singletonList(lamExpr));

        CheckTypeVisitor.Result argResult = myVisitor.typeCheck(arg, Pi(lamParam, binding));
        if (argResult == null) {
          return null;
        }

        Expression expr1 = Apps(argResult.expression, Left());
        Expression expr2 = Apps(argResult.expression, Right());
        result.expression
            .addArgument(expr1)
            .addArgument(expr2)
            .addArgument(argResult.expression);
        result.type = result.type.applyExpressions(Arrays.asList(expr1, expr2, argResult.expression));
        return result;
      }

      List<DependentLink> params = new ArrayList<>();
      result.type = result.type.getPiParameters(params, true, true);
      if (!fixImplicitArgs(result, params, fun)) {
        return null;
      }
    }

    DependentLink param = result.type.getPiParameters();
    if (!param.hasNext()) {
      TypeCheckingError error = new TypeMismatchError(myParentDefinition, new StringPrettyPrintable("A pi type"), result.type, fun);
      fun.setWellTyped(myVisitor.getContext(), new ErrorExpression(result.expression, error));
      myVisitor.getErrorReporter().report(error);
      return null;
    }

    CheckTypeVisitor.Result argResult = myVisitor.typeCheck(arg, param.getType());
    if (argResult == null) {
      return null;
    }

    if (param.isExplicit() != isExplicit) {
      TypeCheckingError error = new TypeCheckingError(myParentDefinition, "Expected an " + (param.isExplicit() ? "explicit" : "implicit") + " argument", arg);
      arg.setWellTyped(myVisitor.getContext(), new ErrorExpression(argResult.expression, error));
      myVisitor.getErrorReporter().report(error);
      return null;
    }

    result.expression = result.expression.addArgument(argResult.expression);
    result.type = result.type.applyExpressions(Collections.singletonList(argResult.expression));
    return result;
  }

  protected CheckTypeVisitor.Result inferArg(Abstract.Expression fun, Abstract.Expression arg, boolean isExplicit, Expression expectedType) {
    CheckTypeVisitor.Result result;
    if (fun instanceof Abstract.AppExpression) {
      Abstract.ArgumentExpression argument = ((Abstract.AppExpression) fun).getArgument();
      result = inferArg(((Abstract.AppExpression) fun).getFunction(), argument.getExpression(), argument.isExplicit(), expectedType);
    } else {
      if (fun instanceof Abstract.DefCallExpression) {
        result = myVisitor.getTypeCheckingDefCall().typeCheckDefCall((Abstract.DefCallExpression) fun);
        if (result != null) {
          fun.setWellTyped(myVisitor.getContext(), result.expression);
        }
      } else {
        result = myVisitor.typeCheck(fun, null);
      }

      if (isExplicit && result != null) {
        ConCallExpression conCall = result.expression.getFunction().toConCall();
        if (conCall != null &&
            result.expression.getArguments().size() < DependentLink.Helper.size(conCall.getDefinition().getDataType().getParameters()) &&
            expectedType != null &&
            !conCall.getDefinition().hasErrors()) {
          Expression expectedTypeNorm = expectedType.normalize(NormalizeVisitor.Mode.WHNF);
          List<? extends Expression> args = expectedTypeNorm.getArguments();
          if (expectedTypeNorm.getFunction().toDataCall() != null) {
            List<Expression> args1 = new ArrayList<>(args.size());
            args1.addAll(result.expression.getArguments());
            args1.addAll(args.subList(result.expression.getArguments().size(), args.size()));
            args = conCall.getDefinition().matchDataTypeArguments(args1);
            if (args != null) {
              if (!conCall.getDataTypeArguments().isEmpty()) {
                args = args.subList(conCall.getDataTypeArguments().size(), args.size());
              }
              if (!args.isEmpty()) {
                result.expression = Apps(result.expression, args);
                result.type = result.type.applyExpressions(args);
              }
          /*  if (dataCall.isPolymorphic()) {
              if (result.getEquations() instanceof DummyEquations) {
                result.setEquations(newEquations());
              }
              //conCall.applyLevelSubst(dataCall.getPolyParamsSubst());
              //result.type.toDataCall().applyLevelSubst(dataCall.getPolyParamsSubst());
              LevelSubstitution levels = conCall.getPolyParamsSubst();
              for (Binding binding : levels.getDomain()) {
                LevelExpression expectedLevel = dataCall.getPolyParamsSubst().get(binding);
                if (expectedLevel != null) {
                  result.getEquations().add(levels.get(binding), expectedLevel, Equations.CMP.EQ, fun);
               //   if (expectedLevel.isBinding() && expectedLevel.getUnitBinding() instanceof InferenceBinding) {
               //     result.addUnsolvedVariable((InferenceBinding) expectedLevel.getUnitBinding());
               //   }
                }
              }
            }/**/
            }
            return inferArg(result, arg, true, fun);
          }
        }
      }
    }

    if (result == null) {
      myVisitor.typeCheck(arg, null);
      return null;
    }
    return inferArg(result, arg, isExplicit, fun);
  }

  @Override
  public CheckTypeVisitor.Result infer(Abstract.AppExpression expr, Expression expectedType) {
    Abstract.ArgumentExpression arg = expr.getArgument();
    return inferArg(expr.getFunction(), arg.getExpression(), arg.isExplicit(), expectedType);
  }

  @Override
  public CheckTypeVisitor.Result infer(Abstract.BinOpExpression expr, Expression expectedType) {
    Concrete.Position position = expr instanceof Concrete.Expression ? ((Concrete.Expression) expr).getPosition() : ConcreteExpressionFactory.POSITION;
    return inferArg(inferArg(new Concrete.DefCallExpression(position, expr.getResolvedBinOp()), expr.getLeft(), true, null), expr.getRight(), true, expr);
  }

  @Override
  public CheckTypeVisitor.Result inferTail(CheckTypeVisitor.Result result, Expression expectedType, Abstract.Expression expr) {
    List<DependentLink> actualParams = new ArrayList<>();
    Type actualType = result.type.getPiParameters(actualParams, true, true);
    List<DependentLink> expectedParams = new ArrayList<>(actualParams.size());
    Expression expectedType1 = expectedType.getPiParameters(expectedParams, true, true);
    if (expectedParams.size() > actualParams.size()) {
      TypeCheckingError error = new TypeMismatchError(myParentDefinition, expectedType1.fromPiParameters(expectedParams), actualType.fromPiParameters(actualParams), expr);
      expr.setWellTyped(myVisitor.getContext(), new ErrorExpression(result.expression, error));
      myVisitor.getErrorReporter().report(error);
      return null;
    }

    if (expectedParams.size() != actualParams.size()) {
      int argsNumber = actualParams.size() - expectedParams.size();
      result.type = actualType.fromPiParameters(actualParams.subList(argsNumber, actualParams.size()));
      if (!fixImplicitArgs(result, actualParams.subList(0, argsNumber), expr)) {
        return null;
      }
      expectedType = expectedType1.fromPiParameters(expectedParams); // TODO: do we need this line?
    }

    return myVisitor.checkResult(expectedType, result, expr);
  }
}
