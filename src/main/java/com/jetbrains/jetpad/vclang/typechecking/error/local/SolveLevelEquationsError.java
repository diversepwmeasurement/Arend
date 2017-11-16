package com.jetbrains.jetpad.vclang.typechecking.error.local;

import com.jetbrains.jetpad.vclang.core.context.binding.LevelVariable;
import com.jetbrains.jetpad.vclang.core.context.binding.Variable;
import com.jetbrains.jetpad.vclang.core.context.binding.inference.InferenceLevelVariable;
import com.jetbrains.jetpad.vclang.error.SourceInfo;
import com.jetbrains.jetpad.vclang.error.doc.Doc;
import com.jetbrains.jetpad.vclang.term.concrete.Concrete;
import com.jetbrains.jetpad.vclang.term.prettyprint.PrettyPrintVisitor;
import com.jetbrains.jetpad.vclang.term.prettyprint.PrettyPrinterConfig;
import com.jetbrains.jetpad.vclang.typechecking.implicitargs.equations.LevelEquation;

import java.util.*;

import static com.jetbrains.jetpad.vclang.error.doc.DocFactory.*;

public class SolveLevelEquationsError extends TypecheckingError {
  public final Collection<? extends LevelEquation<? extends LevelVariable>> equations;

  public SolveLevelEquationsError(Collection<? extends LevelEquation<? extends LevelVariable>> equations, Concrete.SourceNode cause) {
    super("Cannot solve equations", cause);
    this.equations = equations;
  }

  @Override
  public Doc getBodyDoc(PrettyPrinterConfig src) {
    List<Doc> docs = new ArrayList<>(equations.size());
    StringBuilder builder = new StringBuilder();
    PrettyPrintVisitor ppv = new PrettyPrintVisitor(builder, 0, !src.isSingleLine());

    Set<InferenceLevelVariable> variables = new HashSet<>();
    for (LevelEquation<? extends Variable> equation : equations) {
      builder.setLength(0);
      if (equation.isInfinity()) {
        if (equation.getVariable() instanceof InferenceLevelVariable) {
          variables.add((InferenceLevelVariable) equation.getVariable());
        }

        printEqExpr(builder, ppv, equation.getVariable(), null);
        builder.append(" = \\oo");
      } else {
        if (equation.getVariable1() instanceof InferenceLevelVariable) {
          variables.add((InferenceLevelVariable) equation.getVariable1());
        }
        if (equation.getVariable2() instanceof InferenceLevelVariable) {
          variables.add((InferenceLevelVariable) equation.getVariable2());
        }

        printEqExpr(builder, ppv, equation.getVariable1(), -equation.getConstant());
        builder.append(" <= ");
        printEqExpr(builder, ppv, equation.getVariable2(), equation.getConstant());
      }
      docs.add(text(builder.toString()));
    }

    if (variables.isEmpty()) {
      return vList(docs);
    }

    if (variables.size() == 1) {
      InferenceLevelVariable variable = variables.iterator().next();
      Concrete.SourceNode sourceNode = variable.getSourceNode();
      if (sourceNode.getData() != getCause()) {
        String position = sourceNode.getData() instanceof SourceInfo ? ((SourceInfo) sourceNode.getData()).positionTextRepresentation() : null;
        docs.add(hang(hList(text("where " + ppv.getInferLevelVarText(variable) + " is defined"), position != null ? text(" at " + position) : empty(), text(" for")), ppDoc(sourceNode, src)));
      }
    } else {
      List<Doc> varDocs = new ArrayList<>(variables.size());
      for (InferenceLevelVariable variable : variables) {
        Concrete.SourceNode sourceNode = variable.getSourceNode();
        String position = sourceNode.getData() instanceof SourceInfo ? ((SourceInfo) sourceNode.getData()).positionTextRepresentation() : null;
        varDocs.add(hang(hList(text(ppv.getInferLevelVarText(variable)), position != null ? text(" at " + position) : empty(), text(" for")), ppDoc(sourceNode, src)));
      }
      docs.add(hang(text("where variables are defined as follows:"), vList(varDocs)));
    }
    return vList(docs);
  }

  private void printEqExpr(StringBuilder builder, PrettyPrintVisitor ppv, Variable var, Integer constant) {
    if (var != null) {
      if (var instanceof InferenceLevelVariable) {
        ppv.prettyPrintInferLevelVar((InferenceLevelVariable) var);
      } else {
        builder.append(var);
      }
      if (constant != null && constant > 0) {
        builder.append(" + ").append(constant);
      }
    } else {
      builder.append(constant > 0 ? constant : 0);
    }
  }
}
