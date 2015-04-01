package com.jetbrains.jetpad.vclang.term.visitor;

import com.jetbrains.jetpad.vclang.term.expr.Abstract;

import java.util.ArrayList;
import java.util.List;

public class CompareVisitor implements AbstractExpressionVisitor<Abstract.Expression, Boolean> {
  private final List<Equation> myEquations;
  private CMP myCmp;

  public static enum CMP { EQ, GEQ, LEQ }

  private static CMP not(CMP cmp) {
    switch (cmp) {
      case EQ:
        return CMP.EQ;
      case GEQ:
        return CMP.LEQ;
      case LEQ:
        return CMP.GEQ;
      default:
        throw new IllegalStateException();
    }
  }

  private static Abstract.Expression lamArgs(Abstract.Expression expr, List<Abstract.Expression> args) {
    if (expr instanceof Abstract.LamExpression) {
      Abstract.LamExpression lamExpr = (Abstract.LamExpression) expr;
      for (Abstract.Argument arg : lamExpr.getArguments()) {
        if (arg instanceof Abstract.TelescopeArgument) {
          Abstract.TelescopeArgument teleArg = (Abstract.TelescopeArgument) arg;
          for (String ignored : teleArg.getNames()) {
            args.add(teleArg.getType());
          }
        } else
        if (arg instanceof Abstract.NameArgument) {
          args.add(null);
        }
      }
      return lamArgs(lamExpr.getBody(), args);
    } else {
      return expr;
    }
  }

  private static Abstract.Expression piArgs(Abstract.Expression expr, List<Abstract.Expression> args) {
    if (expr instanceof Abstract.PiExpression) {
      Abstract.PiExpression piExpr = (Abstract.PiExpression) expr;
      for (Abstract.Argument arg : piExpr.getArguments()) {
        if (arg instanceof Abstract.TelescopeArgument) {
          Abstract.TelescopeArgument teleArg = (Abstract.TelescopeArgument) arg;
          for (String ignored : teleArg.getNames()) {
            args.add(teleArg.getType());
          }
        } else
        if (arg instanceof Abstract.TypeArgument) {
          args.add(((Abstract.TypeArgument) arg).getType());
        }
      }
      return piArgs(piExpr.getCodomain(), args);
    } else {
      return expr;
    }
  }

  public static class Equation {
    public Abstract.HoleExpression hole;
    public Abstract.Expression expression;

    public Equation(Abstract.HoleExpression hole, Abstract.Expression expression) {
      this.hole = hole;
      this.expression = expression;
    }
  }

  public CompareVisitor(CMP cmp, List<Equation> equations) {
    myCmp = cmp;
    myEquations = equations;
  }

  public List<Equation> equations() {
    return myEquations;
  }

  @Override
  public Boolean visitApp(Abstract.AppExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    if (!(other instanceof Abstract.AppExpression)) return false;
    Abstract.AppExpression otherApp = (Abstract.AppExpression) other;
    return expr.getFunction().accept(this, otherApp.getFunction()) && expr.getArgument().accept(this, otherApp.getArgument());
  }

  @Override
  public Boolean visitDefCall(Abstract.DefCallExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.DefCallExpression && expr.getDefinition().equals(((Abstract.DefCallExpression) other).getDefinition());
  }

  @Override
  public Boolean visitIndex(Abstract.IndexExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.IndexExpression && expr.getIndex() == ((Abstract.IndexExpression) other).getIndex();
  }

  @Override
  public Boolean visitLam(Abstract.LamExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    if (!(other instanceof Abstract.LamExpression)) return false;
    List<Abstract.Expression> args1 = new ArrayList<>();
    Abstract.Expression body1 = lamArgs(expr, args1);
    List<Abstract.Expression> args2 = new ArrayList<>();
    Abstract.Expression body2 = lamArgs(other, args2);
    if (args1.size() != args2.size() || !body1.accept(this, body2)) return false;
    for (int i = 0; i < args1.size(); ++i) {
      if (args1.get(i) != null && args2.get(i) != null && !args1.get(i).accept(this, args2.get(i))) return false;
    }
    return true;
  }

  @Override
  public Boolean visitNat(Abstract.NatExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.NatExpression;
  }

  @Override
  public Boolean visitNelim(Abstract.NelimExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.NelimExpression;
  }

  @Override
  public Boolean visitPi(Abstract.PiExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    if (!(other instanceof Abstract.PiExpression)) return false;
    List<Abstract.Expression> args1 = new ArrayList<>();
    Abstract.Expression codomain1 = piArgs(expr, args1);
    List<Abstract.Expression> args2 = new ArrayList<>();
    Abstract.Expression codomain2 = piArgs(other, args2);
    if (args1.size() != args2.size() || !codomain1.accept(this, codomain2)) return false;
    myCmp = not(myCmp);
    for (int i = 0; i < args1.size(); ++i) {
      if (!args1.get(i).accept(this, args2.get(i))) return false;
    }
    myCmp = not(myCmp);
    return true;
  }

  @Override
  public Boolean visitSuc(Abstract.SucExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.SucExpression;
  }

  @Override
  public Boolean visitUniverse(Abstract.UniverseExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    if (!(other instanceof Abstract.UniverseExpression)) return false;
    Abstract.UniverseExpression otherUniverse = (Abstract.UniverseExpression) other;

    switch (myCmp) {
      case EQ:
        return expr.getLevel() == otherUniverse.getLevel();
      case GEQ:
        return expr.getLevel() == -1 || expr.getLevel() >= otherUniverse.getLevel();
      case LEQ:
        return otherUniverse.getLevel() == -1 || otherUniverse.getLevel() >= expr.getLevel();
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public Boolean visitVar(Abstract.VarExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.VarExpression && expr.getName().equals(((Abstract.VarExpression) other).getName());
  }

  @Override
  public Boolean visitZero(Abstract.ZeroExpression expr, Abstract.Expression other) {
    if (expr == other) return true;
    return other instanceof Abstract.ZeroExpression;
  }

  @Override
  public Boolean visitHole(Abstract.HoleExpression expr, Abstract.Expression other) {
    if (expr instanceof CheckTypeVisitor.InferHoleExpression) {
      myEquations.add(new Equation(expr, other));
      return true;
    }

    return expr == other;
  }
}
