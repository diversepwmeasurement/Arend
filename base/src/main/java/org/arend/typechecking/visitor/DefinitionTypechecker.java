package org.arend.typechecking.visitor;

import org.arend.core.context.LinkList;
import org.arend.core.context.Utils;
import org.arend.core.context.binding.*;
import org.arend.core.context.param.*;
import org.arend.core.definition.*;
import org.arend.core.elimtree.Body;
import org.arend.core.elimtree.ElimBody;
import org.arend.core.elimtree.IntervalElim;
import org.arend.core.expr.*;
import org.arend.core.expr.let.HaveClause;
import org.arend.core.expr.type.Type;
import org.arend.core.expr.type.TypeExpression;
import org.arend.core.expr.visitor.*;
import org.arend.core.pattern.BindingPattern;
import org.arend.core.pattern.ConstructorExpressionPattern;
import org.arend.core.pattern.ExpressionPattern;
import org.arend.core.pattern.Pattern;
import org.arend.core.sort.Level;
import org.arend.core.sort.Sort;
import org.arend.core.subst.*;
import org.arend.ext.core.level.LevelSubstitution;
import org.arend.error.CountingErrorReporter;
import org.arend.error.IncorrectExpressionException;
import org.arend.ext.ArendExtension;
import org.arend.ext.core.definition.CoreFunctionDefinition;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.*;
import org.arend.ext.prettyprinting.doc.DocFactory;
import org.arend.ext.typechecking.LevelProver;
import org.arend.naming.reference.*;
import org.arend.prelude.Prelude;
import org.arend.ext.concrete.definition.ClassFieldKind;
import org.arend.ext.concrete.definition.FunctionKind;
import org.arend.term.concrete.Concrete;
import org.arend.term.concrete.ConcreteDefinitionVisitor;
import org.arend.term.concrete.FreeReferablesVisitor;
import org.arend.typechecking.FieldDFS;
import org.arend.typechecking.LevelContext;
import org.arend.typechecking.covariance.ParametersCovarianceChecker;
import org.arend.typechecking.covariance.RecursiveDataChecker;
import org.arend.typechecking.covariance.UniverseInParametersChecker;
import org.arend.typechecking.covariance.UniverseKindChecker;
import org.arend.typechecking.error.CycleError;
import org.arend.typechecking.error.ErrorReporterCounter;
import org.arend.typechecking.error.local.*;
import org.arend.typechecking.implicitargs.equations.DummyEquations;
import org.arend.typechecking.implicitargs.equations.LevelEquationsSolver;
import org.arend.typechecking.instance.pool.GlobalInstancePool;
import org.arend.typechecking.instance.pool.LocalInstancePool;
import org.arend.typechecking.order.DFS;
import org.arend.typechecking.order.MapDFS;
import org.arend.typechecking.patternmatching.ConditionsChecking;
import org.arend.typechecking.patternmatching.ElimTypechecking;
import org.arend.typechecking.patternmatching.ExtElimClause;
import org.arend.typechecking.patternmatching.PatternTypechecking;
import org.arend.typechecking.result.DefCallResult;
import org.arend.typechecking.result.TypecheckingResult;
import org.arend.ext.util.Pair;

import java.util.*;

import static org.arend.core.expr.ExpressionFactory.*;

public class DefinitionTypechecker extends BaseDefinitionTypechecker implements ConcreteDefinitionVisitor<Void, List<ExtElimClause>> {
  protected CheckTypeVisitor typechecker;
  private boolean myNewDef = true;

  public DefinitionTypechecker(CheckTypeVisitor typechecker) {
    super(typechecker == null ? null : typechecker.getErrorReporter());
    this.typechecker = typechecker;
  }

  public void setTypechecker(CheckTypeVisitor typechecker) {
    this.typechecker = typechecker;
    this.errorReporter = typechecker.getErrorReporter();
  }

  public void updateState(boolean update) {
    myNewDef = !update;
  }

  public Definition typecheckHeader(Definition typechecked, GlobalInstancePool instancePool, Concrete.Definition definition) {
    LocalInstancePool localInstancePool = new LocalInstancePool(typechecker);
    instancePool.setInstancePool(localInstancePool);
    typechecker.setInstancePool(instancePool);

    if (definition instanceof Concrete.BaseFunctionDefinition) {
      FunctionDefinition functionDef = typechecked != null ? (FunctionDefinition) typechecked : ((Concrete.BaseFunctionDefinition) definition).getKind() == FunctionKind.CONS ? new DConstructor(definition.getData()) : new FunctionDefinition(definition.getData());
      if (myNewDef) {
        myNewDef = typechecked == null || typechecked.status().needsTypeChecking();
      }
      if (functionDef.getResultType() == null) {
        functionDef.setResultType(new ErrorExpression());
      }
      functionDef.setStatus(Definition.TypeCheckingStatus.TYPE_CHECKING);
      try {
        typecheckFunctionHeader(functionDef, (Concrete.BaseFunctionDefinition) definition, localInstancePool);
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), definition));
      }
      return functionDef;
    } else
    if (definition instanceof Concrete.DataDefinition) {
      DataDefinition dataDef = typechecked != null ? (DataDefinition) typechecked : new DataDefinition(definition.getData());
      if (myNewDef) {
        myNewDef = typechecked == null || typechecked.status().needsTypeChecking();
      }
      dataDef.setStatus(Definition.TypeCheckingStatus.TYPE_CHECKING);
      try {
        typecheckDataHeader(dataDef, (Concrete.DataDefinition) definition, localInstancePool);
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), definition));
      }
      if (dataDef.getSort() == null || dataDef.getSort().getPLevel().isInfinity()) {
        errorReporter.report(new TypecheckingError("Cannot infer the sort of a recursive data type", definition));
        if (typechecked == null) {
          dataDef.addStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
          dataDef.setSort(Sort.SET0);
        }
      }
      return dataDef;
    } else {
      throw new IllegalStateException();
    }
  }

  public List<ExtElimClause> typecheckBody(Definition definition, Concrete.Definition def, Set<DataDefinition> dataDefinitions) {
    if (definition instanceof FunctionDefinition) {
      try {
        return typecheckFunctionBody((FunctionDefinition) definition, (Concrete.BaseFunctionDefinition) def);
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), def));
      }
    } else
    if (definition instanceof DataDefinition) {
      try {
        if (!typecheckDataBody((DataDefinition) definition, (Concrete.DataDefinition) def, dataDefinitions) && myNewDef) {
          definition.addStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
        }
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), def));
      }
    } else {
      throw new IllegalStateException();
    }
    return null;
  }

  @Override
  public List<ExtElimClause> visitFunction(Concrete.BaseFunctionDefinition def, Void params) {
    Definition typechecked = def.getData().getTypechecked();
    LocalInstancePool localInstancePool = new LocalInstancePool(typechecker);
    typechecker.getInstancePool().setInstancePool(localInstancePool);

    FunctionDefinition definition = typechecked != null ? (FunctionDefinition) typechecked : def.getKind() == FunctionKind.CONS ? new DConstructor(def.getData()) : new FunctionDefinition(def.getData());
    if (myNewDef) {
      myNewDef = typechecked == null || !typechecked.status().headerIsOK();
    }
    typechecker.setDefinition(definition);
    if (definition.getResultType() == null) {
      definition.setResultType(new ErrorExpression());
    }
    definition.setStatus(Definition.TypeCheckingStatus.TYPE_CHECKING);
    try {
      typecheckFunctionHeader(definition, def, localInstancePool);
      if (myNewDef) {
        myNewDef = typechecked == null;
      }
      return typecheckFunctionBody(definition, def);
    } catch (IncorrectExpressionException e) {
      errorReporter.report(new TypecheckingError(e.getMessage(), def));
      return null;
    }
  }

  @Override
  public List<ExtElimClause> visitData(Concrete.DataDefinition def, Void params) {
    Definition typechecked = def.getData().getTypechecked();
    LocalInstancePool localInstancePool = new LocalInstancePool(typechecker);
    typechecker.getInstancePool().setInstancePool(localInstancePool);

    DataDefinition definition = typechecked != null ? (DataDefinition) typechecked : new DataDefinition(def.getData());
    typechecker.setDefinition(definition);
    if (myNewDef) {
      myNewDef = typechecked == null || typechecked.status().needsTypeChecking();
    }
    definition.setStatus(Definition.TypeCheckingStatus.TYPE_CHECKING);
    try {
      typecheckDataHeader(definition, def, localInstancePool);
      if (definition.status().headerIsOK()) {
        if (myNewDef) {
          myNewDef = typechecked == null;
        }
        typecheckDataBody(definition, def, Collections.singleton(definition));
      }
    } catch (IncorrectExpressionException e) {
      errorReporter.report(new TypecheckingError(e.getMessage(), def));
      return null;
    }
    return null;
  }

  @Override
  public List<ExtElimClause> visitClass(Concrete.ClassDefinition def, Void params) {
    Definition typechecked = def.getData().getTypechecked();
    typechecker.setStatus(def.getStatus().getTypecheckingStatus());

    if (def.isRecursive()) {
      errorReporter.report(new TypecheckingError("A class cannot be recursive", def));
      if (typechecked != null) {
        return null;
      }
    }

    ClassDefinition definition = typechecked != null ? (ClassDefinition) typechecked : new ClassDefinition(def.getData());
    typechecker.setDefinition(definition);
    if (myNewDef) {
      myNewDef = typechecked == null || typechecked.status().needsTypeChecking();
    }
    definition.setStatus(Definition.TypeCheckingStatus.TYPE_CHECKING);
    if (typechecked == null) {
      def.getData().setTypecheckedIfNotCancelled(definition);
    }
    if (def.isRecursive()) {
      definition.setStatus(Definition.TypeCheckingStatus.HAS_ERRORS);

      for (Concrete.ClassElement element : def.getElements()) {
        if (element instanceof Concrete.ClassField) {
          addField(((Concrete.ClassField) element).getData(), definition, new PiExpression(Sort.STD, new TypedSingleDependentLink(false, "this", new ClassCallExpression(definition, definition.makeIdLevels()), true), new ErrorExpression()), null).setStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
        }
      }
    } else {
      try {
        typecheckClass(def, definition);
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), def));
      }
    }
    return null;
  }

  private void getFreeVariablesClosure(Expression expression, Set<Binding> freeVars) {
    for (Binding var : FreeVariablesCollector.getFreeVariables(expression)) {
      if (freeVars.add(var)) {
        getFreeVariablesClosure(var.getTypeExpr(), freeVars);
      }
    }
  }

  private void calculateParametersTypecheckingOrder(Definition definition) {
    List<DependentLink> parametersList;
    if (definition instanceof Constructor && ((Constructor) definition).getDataTypeParameters().hasNext()) {
      parametersList = new ArrayList<>(2);
      parametersList.add(((Constructor) definition).getDataTypeParameters());
      parametersList.add(definition.getParameters());
    } else {
      parametersList = Collections.singletonList(definition.getParameters());
    }

    LinkedHashSet<Binding> processed = new LinkedHashSet<>();
    for (DependentLink link : parametersList) {
      boolean isDataTypeParameter = parametersList.size() > 1 && link == parametersList.get(0);
      for (; link.hasNext(); link = link.getNext()) {
        if (processed.contains(link)) {
          continue;
        }
        if (link.isExplicit() && !isDataTypeParameter) {
          processed.add(link);
        } else {
          FreeVariablesClassifier classifier = new FreeVariablesClassifier(link);
          boolean isDataTypeParam = isDataTypeParameter;
          DependentLink link1 = definition instanceof FunctionDefinition ? link : link.getNext(); // if link1 == link, check the result type
          boolean found = false;
          while (true) {
            if (!link1.hasNext()) {
              if (isDataTypeParam) {
                link1 = parametersList.get(1);
                isDataTypeParam = false;
              }
              if (!link1.hasNext()) {
                break;
              }
            }

            boolean checkResultType = link1 == link && definition instanceof FunctionDefinition;
            DependentLink actualLink = checkResultType ? EmptyDependentLink.getInstance() : link1;
            FreeVariablesClassifier.Result result;
            if (checkResultType) {
              Expression type = ((FunctionDefinition) definition).getResultType();
              result = type instanceof ReferenceExpression && ((ReferenceExpression) type).getBinding() == link ? FreeVariablesClassifier.Result.BAD : type.accept(classifier, true);
            } else {
              result = classifier.checkBinding(link1);
            }
            if ((result == FreeVariablesClassifier.Result.GOOD || result == FreeVariablesClassifier.Result.BOTH) && processed.contains(actualLink)) {
              found = true;
              processed.add(link);
              break;
            }
            if (result == FreeVariablesClassifier.Result.GOOD && (checkResultType || link1.isExplicit())) {
              found = true;
              processed.add(link);
              Set<Binding> freeVars = new HashSet<>();
              getFreeVariablesClosure(checkResultType ? ((FunctionDefinition) definition).getResultType() : link1.getTypeExpr(), freeVars);
              for (DependentLink link2 : parametersList) {
                for (; link2.hasNext() && link2 != actualLink; link2 = link2.getNext()) {
                  if (freeVars.contains(link2)) {
                    processed.add(link2);
                  }
                }
                if (!checkResultType && link2 == link1) {
                  break;
                }
              }
              processed.add(actualLink);
              break;
            }

            link1 = link1.getNext();
          }

          if (!found) {
            processed.add(link);
          }
        }
      }
    }

    boolean needReorder = false;
    DependentLink link = parametersList.get(0);
    boolean isDataTypeParameter = parametersList.size() > 1;
    for (Binding binding : processed) {
      if (binding != link) {
        needReorder = true;
        break;
      }
      if (link.hasNext()) {
        link = link.getNext();
        if (!link.hasNext() && isDataTypeParameter) {
          link = parametersList.get(1);
          isDataTypeParameter = false;
        }
      }
    }

    if (needReorder) {
      Map<Binding,Integer> map = new HashMap<>();
      if (definition instanceof FunctionDefinition) {
        map.put(EmptyDependentLink.getInstance(), -1);
      }
      int i = 0;
      for (DependentLink link1 : parametersList) {
        for (; link1.hasNext(); link1 = link1.getNext()) {
          map.put(link1,i);
          i++;
        }
      }

      List<Integer> order = new ArrayList<>(processed.size());
      for (Binding binding : processed) {
        order.add(map.get(binding));
      }
      if (order.get(order.size() - 1) == -1) {
        order.remove(order.size() - 1);
      }

      definition.setParametersTypecheckingOrder(order);
    }
  }

  private UniverseKind checkForUniverses(DependentLink link) {
    UniverseInParametersChecker checker = new UniverseInParametersChecker();
    UniverseKind universeKind = UniverseKind.NO_UNIVERSES;
    for (; link.hasNext(); link = link.getNext()) {
      link = link.getNextTyped(null);
      universeKind = universeKind.max(checker.getUniverseKind(link.getTypeExpr()));
      if (universeKind == UniverseKind.WITH_UNIVERSES) {
        return UniverseKind.WITH_UNIVERSES;
      }
    }
    return universeKind;
  }

  private Integer checkResultTypeLevel(TypecheckingResult result, boolean isLemma, boolean isProperty, Expression resultType, FunctionDefinition funDef, ClassField classField, boolean isOverridden, Concrete.SourceNode sourceNode) {
    if (result == null || resultType == null) {
      return null;
    }

    Integer level = typechecker.getExpressionLevel(EmptyDependentLink.getInstance(), result.type, resultType, DummyEquations.getInstance(), sourceNode);
    if (level != null) {
      if (!checkLevel(isLemma, isProperty, level, null, sourceNode)) {
        if (myNewDef && funDef != null) {
          funDef.setKind(CoreFunctionDefinition.Kind.FUNC);
        }
        if (isProperty) {
          return null;
        }
      }
      if (myNewDef) {
        if (funDef != null) {
          funDef.setResultTypeLevel(result.expression);
        }
        if (!isOverridden && classField != null) {
          classField.setTypeLevel(result.expression);
        }
      }
    }
    return level;
  }

  private Integer typecheckResultTypeLevel(Concrete.Expression resultTypeLevel, boolean isLemma, boolean isProperty, Expression resultType, FunctionDefinition funDef, ClassField classField, boolean isOverridden) {
    if (resultTypeLevel == null) return null;
    if (isLemma || isProperty) {
      Sort sort;
      Type type;
      if (resultType instanceof Type) {
        type = (Type) resultType;
        sort = type.getSortOfType();
      } else {
        sort = resultType.getSortOfType();
        type = sort == null ? null : new TypeExpression(resultType, sort);
      }
      if (type != null) {
        TypedSingleDependentLink y = new TypedSingleDependentLink(true, "y", type);
        UntypedSingleDependentLink x = new UntypedSingleDependentLink("x", y);
        TypecheckingResult result = typechecker.finalCheckExpr(resultTypeLevel, new PiExpression(sort, x, FunCallExpression.make(Prelude.PATH_INFIX, new LevelPair(sort.getPLevel(), sort.getHLevel()), Arrays.asList(resultType, new ReferenceExpression(x), new ReferenceExpression(y)))));
        if (result == null) return null;
        if (myNewDef) {
          if (funDef != null) {
            funDef.setResultTypeLevel(result.expression);
          }
          if (!isOverridden && classField != null) {
            classField.setTypeLevel(result.expression);
          }
        }
        return -1;
      }
    }
    return checkResultTypeLevel(typechecker.finalCheckExpr(resultTypeLevel, null), isLemma, isProperty, resultType, funDef, classField, isOverridden, resultTypeLevel);
  }

  private void calculateGoodThisParameters(Constructor definition) {
    GoodThisParametersVisitor visitor;
    if (definition.getPatterns() == null) {
      visitor = new GoodThisParametersVisitor(definition.getParameters());
    } else {
      visitor = new GoodThisParametersVisitor(Pattern.getFirstBinding(definition.getPatterns()));
      visitor.visitParameters(definition.getParameters(), null);
    }
    visitor.visitBody(definition.getBody(), null);
    definition.setGoodThisParameters(visitor.getGoodParameters());
  }

  private void calculateTypeClassParameters(Concrete.ReferableDefinition refDef, Definition def) {
    List<Definition.TypeClassParameterKind> typeClassParameters = new ArrayList<>();

    if (def instanceof Constructor) {
      Constructor constructor = (Constructor) def;
      List<Definition.TypeClassParameterKind> dataTypeParameters = constructor.getDataType().getTypeClassParameters();
      if (dataTypeParameters.isEmpty()) {
        for (DependentLink link = constructor.getDataTypeParameters(); link.hasNext(); link = link.getNext()) {
          typeClassParameters.add(Definition.TypeClassParameterKind.NO);
        }
      } else {
        List<ExpressionPattern> patterns = constructor.getPatterns();
        if (patterns == null) {
          typeClassParameters.addAll(dataTypeParameters);
        } else {
          assert patterns.size() == dataTypeParameters.size();
          int i = 0;
          for (ExpressionPattern pattern : patterns) {
            if (pattern instanceof BindingPattern) {
              typeClassParameters.add(dataTypeParameters.get(i));
            } else {
              DependentLink next = i + 1 < patterns.size() ? patterns.get(i + 1).getFirstBinding() : EmptyDependentLink.getInstance();
              for (DependentLink link = pattern.getFirstBinding(); link.hasNext() && link != next; link = link.getNext()) {
                typeClassParameters.add(Definition.TypeClassParameterKind.NO);
              }
            }
            i++;
          }
        }
      }
    }

    for (Concrete.Parameter parameter : Objects.requireNonNull(Concrete.getParameters(refDef, true))) {
      boolean isTypeClass = isTypeClassRef(parameter.getType());
      for (int i = 0; i < parameter.getNumberOfParameters(); i++) {
        typeClassParameters.add(isTypeClass ? Definition.TypeClassParameterKind.YES : Definition.TypeClassParameterKind.NO);
      }
    }

    for (Definition.TypeClassParameterKind kind : typeClassParameters) {
      if (kind != Definition.TypeClassParameterKind.NO) {
        def.setTypeClassParameters(typeClassParameters);
        return;
      }
    }
  }

  private boolean isTypeClassRef(Concrete.Expression expr) {
    if (expr == null) {
      return false;
    }
    Referable typeRef = expr.getUnderlyingReferable();
    Definition typeDef = typeRef instanceof TCDefReferable ? ((TCDefReferable) typeRef).getTypechecked() : null;
    return typeDef instanceof ClassDefinition && !((ClassDefinition) typeDef).isRecord();
  }

  private Pair<Sort,Expression> typecheckParameters(Concrete.ReferableDefinition def, Definition typedDef, LinkList list, LocalInstancePool localInstancePool, Sort expectedSort, DependentLink oldParameters, PiExpression fieldType) {
    Sort sort = Sort.PROP;

    if (oldParameters != null) {
      list.append(oldParameters);
      fieldType = null;
    }

    boolean isClassCoclause = def instanceof Concrete.CoClauseFunctionDefinition && ((Concrete.CoClauseFunctionDefinition) def).getKind() == FunctionKind.CLASS_COCLAUSE;
    DependentLink thisRef = fieldType == null || isClassCoclause ? null : fieldType.getParameters();
    Expression resultType = fieldType == null ? null : isClassCoclause ? fieldType : fieldType.getCodomain();
    ExprSubstitution substitution = fieldType == null ? null : new ExprSubstitution();
    int skip = def instanceof Concrete.CoClauseFunctionDefinition ? ((Concrete.CoClauseFunctionDefinition) def).getNumberOfExternalParameters() : 0;
    Expression expectedType = expectedSort == null ? Type.OMEGA : new UniverseExpression(expectedSort);

    boolean first = true;
    for (Concrete.Parameter parameter : Objects.requireNonNull(Concrete.getParameters(def, true))) {
      if (skip == 0 && resultType != null && !(resultType instanceof ErrorExpression)) {
        resultType = resultType.normalize(NormalizationMode.WHNF).getUnderlyingExpression();
      }

      Type paramResult = null;
      if (parameter.getType() != null) {
        paramResult = def instanceof Concrete.Constructor ? typechecker.checkType(parameter.getType(), expectedType) : typechecker.finalCheckType(parameter.getType(), expectedType, false);
      } else if (skip == 0) {
        if (resultType instanceof PiExpression) {
          SingleDependentLink param = ((PiExpression) resultType).getParameters();
          if (param.isExplicit() != parameter.isExplicit()) {
            errorReporter.report(new ArgumentExplicitnessError(param.isExplicit(), parameter));
            break;
          }
          Type paramType = param.getType();
          if (thisRef != null && paramType.getExpr().findBinding(thisRef)) {
            errorReporter.report(new TypeFromFieldError(TypeFromFieldError.parameter(), paramType.getExpr(), parameter));
          } else {
            paramResult = paramType.subst(new SubstVisitor(substitution, LevelSubstitution.EMPTY));
          }
        } else if (resultType == null || !resultType.reportIfError(errorReporter, parameter)) {
          if (resultType == null) {
            errorReporter.report(new TypecheckingError("Expected a typed parameter", parameter));
          } else {
            errorReporter.report(new FieldTypeParameterError(fieldType.getCodomain(), parameter));
            resultType = new ErrorExpression();
          }
        }
      }
      if (paramResult == null) {
        paramResult = new TypeExpression(new ErrorExpression(), Sort.SET0);
      }
      if (!(def instanceof Concrete.Constructor)) {
        sort = sort.max(paramResult.getSortOfType());
      }

      DependentLink param;
      int numberOfParameters;
      boolean oldParametersOK = true;
      if (parameter instanceof Concrete.TelescopeParameter) {
        List<? extends Referable> referableList = parameter.getReferableList();
        if (referableList.isEmpty()) {
          errorReporter.report(new TypecheckingError("Empty parameter list", parameter));
          continue;
        }

        List<String> names = parameter.getNames();
        param = oldParameters != null
          ? oldParameters
          : referableList.size() == 1 && referableList.get(0) instanceof HiddenLocalReferable
            ? new TypedDependentLink(parameter.isExplicit(), names.get(0), paramResult, true, EmptyDependentLink.getInstance())
            : parameter(parameter.isExplicit(), names, paramResult);
        numberOfParameters = names.size();

        if (oldParameters == null) {
          int i = 0;
          for (DependentLink link = param; link.hasNext(); link = link.getNext(), i++) {
            typechecker.addBinding(referableList.get(i), link);
          }
        } else {
          for (int i = 0; i < names.size(); i++) {
            if (oldParameters.hasNext()) {
              typechecker.addBinding(referableList.get(i), oldParameters);
              oldParameters = oldParameters.getNext();
            } else {
              oldParametersOK = false;
              break;
            }
          }
        }
      } else {
        numberOfParameters = 1;
        if (oldParameters != null) {
          param = oldParameters;
          if (oldParameters.hasNext()) {
            typechecker.addBinding(parameter instanceof Concrete.NameParameter ? ((Concrete.NameParameter) parameter).getReferable() : null, oldParameters);
            oldParameters = oldParameters.getNext();
          } else {
            oldParametersOK = false;
          }
        } else {
          Referable ref = parameter.getReferableList().get(0);
          param = parameter(parameter.isExplicit(), ref == null ? null : ref.getRefName(), paramResult);
          typechecker.addBinding(ref, param);
        }
      }
      if (!oldParametersOK) {
        errorReporter.report(new TypecheckingError("Cannot typecheck definition. Try to clear cache", parameter));
        return null;
      }

      if (resultType != null && skip == 0) {
        for (DependentLink link = param; link.hasNext(); link = link.getNext()) {
          if (!(resultType instanceof PiExpression)) {
            if (!resultType.reportIfError(errorReporter, parameter)) {
              errorReporter.report(new FieldTypeParameterError(fieldType, parameter));
              resultType = new ErrorExpression();
            }
            break;
          }

          PiExpression piExpr = (PiExpression) resultType;
          substitution.add(piExpr.getParameters(), new ReferenceExpression(param));
          if (piExpr.getParameters().getNext().hasNext()) {
            resultType = new PiExpression(piExpr.getResultSort(), piExpr.getParameters().getNext(), piExpr.getCodomain());
          } else {
            resultType = piExpr.getCodomain().normalize(NormalizationMode.WHNF).getUnderlyingExpression();
          }
        }
      }

      if (localInstancePool != null && paramResult instanceof ClassCallExpression) {
        ClassDefinition classDef = ((ClassCallExpression) paramResult).getDefinition();
        if (!classDef.isRecord()) {
          ClassField classifyingField = classDef.getClassifyingField();
          int i = 0;
          for (DependentLink link = param; i < numberOfParameters; link = link.getNext(), i++) {
            ReferenceExpression reference = new ReferenceExpression(link);
            if (classifyingField == null) {
              localInstancePool.addLocalInstance(null, classDef, reference);
            } else {
              localInstancePool.addLocalInstance(FieldCallExpression.make(classifyingField, ((ClassCallExpression) paramResult).getLevels(classifyingField.getParentClass()), reference), classDef, reference);
            }
          }
        }

        if (first && def instanceof Concrete.Definition && ((Concrete.Definition) def).enclosingClass != null) {
          addEnclosingClassInstances((Concrete.Definition) def, param, ((ClassCallExpression) paramResult).getLevels(), localInstancePool);
        }
      }

      if (oldParameters == null) {
        list.append(param);
      }

      if (first && myNewDef && typedDef != null) {
        typedDef.setParameters(param);
      }

      first = false;
      if (skip > 0) skip--;
    }

    return new Pair<>(sort, resultType == null ? null : resultType.subst(substitution));
  }

  private List<Boolean> getStrictParameters(List<? extends Concrete.Parameter> parameters) {
    boolean hasStrict = false;
    for (Concrete.Parameter parameter : parameters) {
      if (parameter.isStrict()) {
        hasStrict = true;
        break;
      }
    }

    if (!hasStrict) {
      return Collections.emptyList();
    }

    List<Boolean> result = new ArrayList<>();
    for (Concrete.Parameter parameter : parameters) {
      for (Referable ignored : parameter.getReferableList()) {
        result.add(parameter.isStrict());
      }
    }
    return result;
  }

  private boolean checkLevel(boolean isLemma, boolean isProperty, Integer level, Sort actualSort, Concrete.SourceNode sourceNode) {
    if ((isLemma || isProperty) && (level == null || level != -1)) {
      Sort sort = level != null ? new Sort(new Level(LevelVariable.PVAR), new Level(actualSort == null || !actualSort.getHLevel().isClosed() ? level : Math.min(level, actualSort.getHLevel().getConstant()))) : actualSort;
      errorReporter.report(new LevelMismatchError(isLemma, sort, sourceNode));
      return false;
    } else {
      return true;
    }
  }

  private static boolean checkResultTypeLater(Concrete.BaseFunctionDefinition def) {
    return def.getBody() instanceof Concrete.TermFunctionBody && !def.isRecursive() && def.getKind() != FunctionKind.LEVEL && (!(def.getBody().getTerm() instanceof Concrete.CaseExpression) || ((Concrete.CaseExpression) def.getBody().getTerm()).getResultType() != null);
  }

  private static void addFieldInstance(ClassField field, ClassDefinition classDef, List<LocalInstance> instances, boolean onlyOverridden) {
    PiExpression overriddenType = classDef.getOverriddenType(field);
    if (onlyOverridden && overriddenType == null) return;
    Expression fieldType = overriddenType != null ? overriddenType.getCodomain() : field.getResultType();
    if (fieldType instanceof ClassCallExpression) {
      ClassCallExpression classCall = (ClassCallExpression) fieldType;
      if (!classCall.getDefinition().isRecord()) {
        instances.add(new LocalInstance(classCall, field));
      }
    }
  }

  private void addEnclosingClassInstances(Concrete.Definition def, Binding thisParam, Levels levels, LocalInstancePool localInstancePool) {
    Definition classDef = def.enclosingClass.getTypechecked();
    if (!(classDef instanceof ClassDefinition)) {
      return;
    }

    List<LocalInstance> instances = new ArrayList<>();
    for (ClassField field : ((ClassDefinition) classDef).getFields()) {
      addFieldInstance(field, (ClassDefinition) classDef, instances, false);
    }

    addLocalInstances(instances, thisParam, levels, (ClassDefinition) classDef, false, localInstancePool);
  }

  private void checkNoStrictParameters(List<? extends Concrete.Parameter> parameters) {
    for (Concrete.Parameter parameter : parameters) {
      if (parameter.isStrict()) {
        errorReporter.report(new TypecheckingError(GeneralError.Level.WARNING_UNUSED, "\\strict is ignored", parameter));
      }
    }
  }

  private boolean typecheckLevelParameters(Concrete.LevelParameters params, LevelVariable base, List<LevelVariable> parameters, Map<LevelReferable, ParamLevelVariable> variables) {
    if (params == null) {
      parameters.add(base);
      return true;
    }
    for (int i = 0; i < params.referables.size(); i++) {
      LevelReferable ref = params.referables.get(i);
      ParamLevelVariable var = new ParamLevelVariable(base.getType(), ref.getRefName(), i, params.isIncreasing ? i : params.referables.size() - 1 - i);
      parameters.add(var);
      variables.put(ref, var);
    }
    return !params.referables.isEmpty();
  }

  private List<LevelVariable> typecheckLevelParameters(Concrete.Definition def) {
    if (def.getPLevelParameters() == null && def.getHLevelParameters() == null) return null;
    List<LevelVariable> parameters = new ArrayList<>();
    Map<LevelReferable, ParamLevelVariable> variables = new HashMap<>();
    boolean isPBased = typecheckLevelParameters(def.getPLevelParameters(), LevelVariable.PVAR, parameters, variables);
    boolean isHBased = typecheckLevelParameters(def.getHLevelParameters(), LevelVariable.HVAR, parameters, variables);
    typechecker.setLevelContext(new LevelContext(variables, isPBased, isHBased));
    return parameters;
  }

  @SuppressWarnings("UnusedReturnValue")
  private boolean typecheckFunctionHeader(FunctionDefinition typedDef, Concrete.BaseFunctionDefinition def, LocalInstancePool localInstancePool) {
    def.getData().setTypecheckedIfNotCancelled(typedDef);
    if (def.enclosingClass != null) {
      typedDef.setHasEnclosingClass(true);
    }
    ClassField implementedField = def instanceof Concrete.CoClauseFunctionDefinition ? typechecker.referableToClassField(((Concrete.CoClauseFunctionDefinition) def).getImplementedField(), def) : null;
    FunctionKind kind = implementedField == null ? def.getKind() : implementedField.isProperty() && implementedField.getTypeLevel() == null ? FunctionKind.LEMMA : FunctionKind.FUNC;
    checkFunctionLevel(def, kind);

    if (def.getKind() == FunctionKind.LEVEL) {
      Definition useParent = def.getUseParent().getTypechecked();
      if (def.getPLevelParameters() == null && useParent.hasNonTrivialPLevelParameters()) {
        def.setPLevelParameters(Concrete.LevelParameters.makeLevelParameters(useParent.getLevelParameters().subList(0, useParent.getNumberOfPLevelParameters())));
      }
      if (def.getHLevelParameters() == null && useParent.hasNonTrivialHLevelParameters()) {
        def.setHLevelParameters(Concrete.LevelParameters.makeLevelParameters(useParent.getLevelParameters().subList(useParent.getNumberOfPLevelParameters(), useParent.getLevelParameters().size())));
      }
    }

    if (myNewDef) {
      typedDef.setLevelParameters(typecheckLevelParameters(def));
      if (def.getKind() == FunctionKind.CLASS_COCLAUSE) {
        Definition enclosingClass = def.enclosingClass.getTypechecked();
        List<? extends LevelVariable> params = enclosingClass.getLevelParameters();
        if (params != null) {
          if (typedDef.getLevelParameters() == null) {
            typedDef.setLevelParameters(new ArrayList<>(params));
          } else {
            boolean setPLevel = def.getPLevelParameters() == null && enclosingClass.hasNonTrivialPLevelParameters();
            boolean setHLevel = def.getHLevelParameters() == null && enclosingClass.hasNonTrivialHLevelParameters();
            if (setPLevel || setHLevel) {
              List<LevelVariable> newParams = new ArrayList<>();
              newParams.addAll(setPLevel ? enclosingClass.getLevelParameters().subList(0, enclosingClass.getNumberOfPLevelParameters()) : typedDef.getLevelParameters().subList(0, typedDef.getNumberOfPLevelParameters()));
              newParams.addAll(setHLevel ? enclosingClass.getLevelParameters().subList(enclosingClass.getNumberOfPLevelParameters(), enclosingClass.getLevelParameters().size()) : typedDef.getLevelParameters().subList(typedDef.getNumberOfPLevelParameters(), typedDef.getLevelParameters().size()));
              typedDef.setLevelParameters(newParams);
            }
          }
        }
      }
    }

    LinkList list = new LinkList();
    Pair<Sort, Expression> pair = typecheckParameters(def, typedDef, list, localInstancePool, null, myNewDef ? null : typedDef.getParameters(), implementedField == null ? null : implementedField.getType());
    if (def.getBody() instanceof Concrete.CoelimFunctionBody || def.getBody() instanceof Concrete.ElimFunctionBody && def.getBody().getClauses().isEmpty()) {
      checkNoStrictParameters(def.getParameters());
    } else if (myNewDef) {
      typedDef.setStrictParameters(getStrictParameters(def.getParameters()));
    }

    if (def.getKind() == FunctionKind.TYPE && def.getBody() instanceof Concrete.ElimFunctionBody && def.getResultType() == null) {
      def.setResultType(new Concrete.UniverseExpression(def.getData(), null, null));
    }

    Expression expectedType = null;
    Concrete.Expression cResultType = def.getResultType();
    if (cResultType != null) {
      Type expectedTypeResult = pair == null
        ? new ErrorExpression()
        : def.getBody() instanceof Concrete.CoelimFunctionBody && !def.isRecursive()
          ? null // The result type will be typechecked together with all field implementations during body typechecking.
          : checkResultTypeLater(def)
            ? typechecker.checkType(cResultType, Type.OMEGA)
            : typechecker.finalCheckType(cResultType, Type.OMEGA, kind == FunctionKind.LEMMA && def.getResultTypeLevel() == null);
      if (expectedTypeResult != null) {
        expectedType = expectedTypeResult.getExpr();
      }
    }

    if (myNewDef) {
      if (pair != null && pair.proj2 != null && cResultType == null && implementedField != null) {
        expectedType = pair.proj2;
        if (expectedType.findBinding(implementedField.getType().getParameters())) {
          errorReporter.report(new TypeFromFieldError(TypeFromFieldError.resultType(), expectedType, def));
          expectedType = null;
        }
      }
      if (expectedType == null) {
        expectedType = new ErrorExpression();
      }

      typedDef.setResultType(expectedType);
      typedDef.setKind(kind.isSFunc() ? (kind == FunctionKind.LEMMA ? CoreFunctionDefinition.Kind.LEMMA : kind == FunctionKind.TYPE ? CoreFunctionDefinition.Kind.TYPE : CoreFunctionDefinition.Kind.SFUNC) : kind == FunctionKind.INSTANCE ? CoreFunctionDefinition.Kind.INSTANCE : CoreFunctionDefinition.Kind.FUNC);

      calculateTypeClassParameters(def, typedDef);
      calculateParametersTypecheckingOrder(typedDef);
    }

    if (myNewDef) {
      GoodThisParametersVisitor goodThisParametersVisitor;
      goodThisParametersVisitor = new GoodThisParametersVisitor(typedDef.getParameters());
      expectedType.accept(goodThisParametersVisitor, null);
      if (typedDef.getResultTypeLevel() != null) {
        typedDef.getResultTypeLevel().accept(goodThisParametersVisitor, null);
      }
      typedDef.setGoodThisParameters(goodThisParametersVisitor.getGoodParameters());
    }

    return pair != null;
  }

  private Pair<Expression,ClassCallExpression> typecheckCoClauses(FunctionDefinition typedDef, Concrete.BaseFunctionDefinition def, FunctionKind kind, List<Concrete.CoClauseElement> elements) {
    List<Concrete.Argument> arguments = new ArrayList<>();
    for (Concrete.Parameter parameter : def.getParameters()) {
      for (Referable referable : parameter.getReferableList()) {
        arguments.add(new Concrete.Argument(new Concrete.ReferenceExpression(def.getData(), referable), false));
      }
    }

    List<Concrete.ClassFieldImpl> classFieldImpls = new ArrayList<>(elements.size());
    for (Concrete.CoClauseElement element : elements) {
      if (element instanceof Concrete.ClassFieldImpl) {
        classFieldImpls.add((Concrete.ClassFieldImpl) element);
      } else {
        throw new IllegalStateException();
      }
      if (element instanceof Concrete.CoClauseFunctionReference && !def.getParameters().isEmpty()) {
        Concrete.Expression oldImpl = ((Concrete.CoClauseFunctionReference) element).implementation;
        ((Concrete.CoClauseFunctionReference) element).implementation = Concrete.AppExpression.make(oldImpl.getData(), oldImpl, arguments);
      }
    }

    Concrete.Expression resultType = def.getResultType();
    if (typedDef.isSFunc() || kind == FunctionKind.CONS) {
      TypecheckingResult typeResult = typechecker.finalCheckExpr(resultType, Type.OMEGA);
      if (typeResult == null || !(typeResult.expression instanceof ClassCallExpression)) {
        return null;
      }
      ClassCallExpression type = (ClassCallExpression) typeResult.expression;
      Set<ClassField> pseudoImplemented = new HashSet<>();
      TypecheckingResult result = typechecker.finalize(typechecker.typecheckClassExt(classFieldImpls, Type.OMEGA, type, pseudoImplemented, resultType, true), def, false);
      if (result == null) return null;

      Expression resultExpr = result.expression.normalize(NormalizationMode.WHNF);
      if (!(resultExpr instanceof ClassCallExpression)) {
        errorReporter.report(new TypeMismatchError(DocFactory.text("a classCall"), resultExpr, def.getResultType()));
        return null;
      }
      ClassCallExpression classCall = (ClassCallExpression) resultExpr;
      typechecker.checkAllImplemented(classCall, pseudoImplemented, def);
      if (classCall.getDefinition() == Prelude.DEP_ARRAY) {
        classCall.getImplementedHere().remove(Prelude.ARRAY_AT);
        LevelPair levels = classCall.getLevels().toLevelPair();
        classCall.setSort(new Sort(levels.get(LevelVariable.PVAR), levels.get(LevelVariable.HVAR).max(new Level(0))));
      }
      return new Pair<>(new NewExpression(null, classCall), type);
    } else {
      Set<ClassField> implemented = new HashSet<>();
      for (Concrete.ClassFieldImpl impl : classFieldImpls) {
        Referable ref = impl.getImplementedField();
        Definition implDef = ref instanceof TCDefReferable ? ((TCDefReferable) ref).getTypechecked() : null;
        if (implDef instanceof ClassField) {
          implemented.add((ClassField) implDef);
        } else if (implDef instanceof ClassDefinition) {
          implemented.addAll(((ClassDefinition) implDef).getFields());
        }
      }

      TypecheckingResult result = typechecker.finalCheckExpr(new Concrete.NewExpression(def.getData(), Concrete.ClassExtExpression.make(def.getData(), typechecker.desugarClassApp(resultType, true, implemented), new Concrete.Coclauses(def.getData(), classFieldImpls))), null);
      if (result == null) return null;
      if (!(result.type instanceof ClassCallExpression)) {
        throw new IllegalStateException();
      }
      return new Pair<>(result.expression, (ClassCallExpression) result.type);
    }
  }

  private ExpressionPattern checkDConstructor(Expression expr, Set<DependentLink> usedVars, Concrete.SourceNode sourceNode) {
    if (expr instanceof ReferenceExpression && ((ReferenceExpression) expr).getBinding() instanceof DependentLink) {
      DependentLink var = (DependentLink) ((ReferenceExpression) expr).getBinding();
      if (!usedVars.add(var)) {
        errorReporter.report(new TypecheckingError("Variable '" + var.getName() + "' occurs multiple times in the body of \\cons", sourceNode));
        return null;
      }
      return new BindingPattern(var);
    }

    if (expr instanceof IntegerExpression) {
      int n;
      try {
        n = checkNumberInPattern(((IntegerExpression) expr).getSmallInteger(), errorReporter, sourceNode);
      } catch (ArithmeticException e) {
        n = Concrete.NumberPattern.MAX_VALUE;
      }
      ExpressionPattern pattern = new ConstructorExpressionPattern(new ConCallExpression(Prelude.ZERO, Levels.EMPTY, Collections.emptyList(), Collections.emptyList()), Collections.emptyList());
      for (int i = 0; i < n; i++) {
        pattern = new ConstructorExpressionPattern(new ConCallExpression(Prelude.SUC, Levels.EMPTY, Collections.emptyList(), Collections.emptyList()), Collections.singletonList(pattern));
      }
      return pattern;
    }

    if (!(expr instanceof ConCallExpression || expr instanceof FunCallExpression && ((FunCallExpression) expr).getDefinition() instanceof DConstructor || expr instanceof TupleExpression)) {
      if (!(expr instanceof ErrorExpression)) {
        errorReporter.report(new TypecheckingError("\\cons must contain only constructors and variables", sourceNode));
      }
      return null;
    }

    List<ExpressionPattern> patterns = new ArrayList<>();
    List<? extends Expression> arguments = expr instanceof DefCallExpression ? ((DefCallExpression) expr).getConCallArguments() : ((TupleExpression) expr).getFields();
    for (Expression argument : arguments) {
      ExpressionPattern pattern = checkDConstructor(argument, usedVars, sourceNode);
      if (pattern == null) {
        return null;
      }
      patterns.add(pattern);
    }

    if (expr instanceof ConCallExpression) {
      ConCallExpression conCall = (ConCallExpression) expr;
      return new ConstructorExpressionPattern(new ConCallExpression(conCall.getDefinition(), conCall.getLevels(), conCall.getDataTypeArguments(), Collections.emptyList()), patterns);
    }

    if (expr instanceof TupleExpression) {
      return new ConstructorExpressionPattern(((TupleExpression) expr).getSigmaType(), patterns);
    }

    DConstructor constructor = (DConstructor) ((FunCallExpression) expr).getDefinition();
    ExpressionPattern pattern = constructor.getPattern();
    if (pattern == null) {
      return null;
    }

    Map<DependentLink, ExpressionPattern> patternSubst = new HashMap<>();
    DependentLink link = DependentLink.Helper.get(constructor.getParameters(), constructor.getNumberOfParameters());
    for (ExpressionPattern patternArg : patterns) {
      patternSubst.put(link, patternArg);
      link = link.getNext();
    }
    DefCallExpression defCall = (DefCallExpression) expr;
    return pattern.subst(new ExprSubstitution().add(constructor.getParameters(), defCall.getDefCallArguments().subList(0, constructor.getNumberOfParameters())), defCall.getLevelSubstitution(), patternSubst);
  }

  private ExpressionPattern checkDConstructor(ClassCallExpression type, NewExpression expr, Set<DependentLink> usedVars, Concrete.SourceNode sourceNode) {
    List<ExpressionPattern> patterns = new ArrayList<>();
    for (ClassField field : type.getDefinition().getFields()) {
      if (!type.isImplemented(field)) {
        ExpressionPattern pattern = checkDConstructor(expr.getImplementation(field), usedVars, sourceNode);
        if (pattern == null) {
          return null;
        }
        patterns.add(pattern);
      }
    }
    return new ConstructorExpressionPattern(type, patterns);
  }

  private void checkCanBeLemma(FunctionDefinition typedDef, Concrete.BaseFunctionDefinition def) {
    if (!((def.getKind() == FunctionKind.FUNC || def.getKind() == FunctionKind.SFUNC) && isBoxed(typedDef))) return;
    Expression type = typedDef.getResultType().normalize(NormalizationMode.WHNF);
    boolean ok = true;
    if (type instanceof ClassCallExpression) {
      List<ClassField> implemented = new ArrayList<>();
      for (ClassField field : ((ClassCallExpression) type).getImplementedHere().keySet()) {
        if (!field.isProperty()) {
          implemented.add(field);
        }
      }
      if (!implemented.isEmpty()) {
        if (def.getResultType() instanceof Concrete.ClassExtExpression) {
          Set<ClassField> concreteImpl = new HashSet<>();
          for (Concrete.ClassFieldImpl fieldImpl : ((Concrete.ClassExtExpression) def.getResultType()).getCoclauses().getCoclauseList()) {
            Referable ref = fieldImpl.getImplementedField();
            if (ref instanceof TCDefReferable) {
              Definition refDef = ((TCDefReferable) ref).getTypechecked();
              if (refDef instanceof ClassField) {
                concreteImpl.add((ClassField) refDef);
              } else if (refDef instanceof ClassDefinition) {
                concreteImpl.addAll(((ClassDefinition) refDef).getFields());
              }
            }
          }
          for (ClassField field : implemented) {
            if (!concreteImpl.contains(field)) {
              ok = false;
              break;
            }
          }
        } else {
          ok = false;
        }
      }
    }
    if (ok) {
      errorReporter.report(new CertainTypecheckingError(CertainTypecheckingError.Kind.COULD_BE_LEMMA, def));
    }
  }

  public static boolean isBoxed(FunctionDefinition def) {
    Body body = def.getActualBody();
    if (!(body instanceof Expression || body == null && def.getBodyHiddenStatus() == FunctionDefinition.HiddenStatus.REALLY_HIDDEN)) return false;
    Expression expr = (Expression) body;
    Expression type = def.getResultType();
    if (type != null && !type.isError() && (expr == null || expr.isBoxed() && !expr.isError())) {
      type = type.normalize(NormalizationMode.WHNF);
      if (expr != null) {
        return !(type instanceof ClassCallExpression) || ((ClassCallExpression) type).getSort().isProp();
      } else {
        Sort sort = type.getSortOfType();
        return sort != null && sort.isProp();
      }
    } else {
      return false;
    }
  }

  private Integer checkTypeLevel(Concrete.BaseFunctionDefinition def, FunctionDefinition typedDef, boolean checked) {
    if (checked && isBoxed(typedDef)) {
      return -1;
    }

    Expression type = typedDef.getResultType();
    Integer resultTypeLevel = type.isError() ? null : typecheckResultTypeLevel(def.getResultTypeLevel(), def.getKind() == FunctionKind.LEMMA, false, type, typedDef, null, false);
    if (resultTypeLevel == null && !type.isError()) {
      DefCallExpression defCall = type.cast(DefCallExpression.class);
      resultTypeLevel = defCall == null ? null : defCall.getUseLevel();
      if (resultTypeLevel == null) {
        defCall = type.getPiParameters(null, false).cast(DefCallExpression.class);
        if (defCall != null) {
          resultTypeLevel = defCall.getUseLevel();
        }
      }
    }

    if (def.getKind() == FunctionKind.LEMMA && resultTypeLevel == null && !type.isError()) {
      Sort sort = type.getSortOfType();
      if (sort == null || !sort.isProp()) {
        DefCallExpression defCall = type.cast(DefCallExpression.class);
        Integer level = defCall == null ? null : defCall.getUseLevel();
        Concrete.SourceNode sourceNode = def.getResultType() != null ? def.getResultType() : def;
        if ((level == null || level != -1) && sort != null && typechecker.getExtension() != null) {
          LevelProver prover = typechecker.getExtension().getLevelProver();
          if (prover != null) {
            TypecheckingResult result = typechecker.finalize(TypecheckingResult.fromChecked(prover.prove(null, type, CheckTypeVisitor.getLevelExpression(new TypeExpression(type, sort), -1), -1, sourceNode, typechecker)), sourceNode, false);
            if (result != null) {
              Integer level2 = checkResultTypeLevel(result, true, false, type, typedDef, null, false, def);
              return level != null && level2 != null ? Integer.valueOf(Math.min(level, level2)) : level != null ? level : level2;
            }
          }
        }
        if (!checkLevel(true, false, level, sort, sourceNode)) {
          if (myNewDef) {
            typedDef.setKind(CoreFunctionDefinition.Kind.SFUNC);
          }
        }
      }
    }

    return resultTypeLevel;
  }

  private List<ExtElimClause> typecheckFunctionBody(FunctionDefinition typedDef, Concrete.BaseFunctionDefinition def) {
    if (myNewDef) {
      typedDef.setUniverseKind(UniverseKind.NO_UNIVERSES);
    }

    FunctionKind kind = def.getKind();
    if (def instanceof Concrete.CoClauseFunctionDefinition) {
      Referable ref = ((Concrete.CoClauseFunctionDefinition) def).getImplementedField();
      if (ref instanceof TCDefReferable) {
        Definition fieldDef = ((TCDefReferable) ref).getTypechecked();
        if (fieldDef instanceof ClassField && DependentLink.Helper.size(typedDef.getParameters()) != Concrete.getNumberOfParameters(def.getParameters())) {
          if (myNewDef) {
            typechecker.setStatus(def.getStatus().getTypecheckingStatus());
            typedDef.addStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
            def.setTypechecked();
          }
          return null;
        }

        if (fieldDef instanceof ClassField && ((ClassField) fieldDef).isProperty()) {
          if (((ClassField) fieldDef).getTypeLevel() == null) {
            kind = FunctionKind.LEMMA;
          } else if (def.getResultType() == null) {
            boolean ok = true;
            for (Concrete.Parameter parameter : def.getParameters()) {
              if (parameter.getType() != null) {
                ok = false;
              }
            }
            if (ok) {
              kind = FunctionKind.LEMMA;
            }
          }
        }
      }
      if (kind != FunctionKind.LEMMA) {
        kind = FunctionKind.FUNC;
      }
    }

    if (typedDef.getResultType() == null) {
      typedDef.setResultType(new ErrorExpression());
    }
    Expression expectedType = typedDef.getResultType();

    List<ExtElimClause> clauses = null;
    Concrete.FunctionBody body = def.getBody();
    boolean checkLevelNow = (body instanceof Concrete.ElimFunctionBody || body.getTerm() instanceof Concrete.CaseExpression && def.getKind() != FunctionKind.LEVEL) && !checkResultTypeLater(def);
    Integer typeLevel = checkLevelNow ? checkTypeLevel(def, typedDef, false) : null;
    if (typeLevel != null && typedDef.isSFunc()) {
      if (body instanceof Concrete.ElimFunctionBody) {
        for (Concrete.FunctionClause clause : body.getClauses()) {
          CheckTypeVisitor.setCaseLevel(clause.getExpression(), typeLevel);
        }
      } else {
        CheckTypeVisitor.setCaseLevel(body.getTerm(), typeLevel);
      }
    }

    boolean bodyIsOK = false;
    ClassCallExpression consType = null;
    if (def.getKind() == FunctionKind.LEVEL && typedDef.getResultType() instanceof UniverseExpression && ((UniverseExpression) typedDef.getResultType()).getSort().getHLevel().isClosed() && (body instanceof Concrete.TermFunctionBody || body instanceof Concrete.ElimFunctionBody && body.getClauses().isEmpty())) {
      ArendExtension extension = typechecker.getExtension();
      LevelProver prover = extension == null ? null : extension.getLevelProver();
      Definition useParent = def.getUseParent() == null ? null : def.getUseParent().getTypechecked();
      if (prover != null && useParent != null && (!typedDef.getParameters().hasNext() || DependentLink.Helper.size(typedDef.getParameters()) == DependentLink.Helper.size(useParent.getParameters()))) {
        try (var ignored = new Utils.SetContextSaver<>(typechecker.getContext())) {
          boolean ok = true;
          if (typedDef.getParameters().hasNext()) {
            ExprSubstitution substitution = new ExprSubstitution();
            DependentLink useParam = useParent.getParameters();
            for (DependentLink param = typedDef.getParameters(); param.hasNext(); param = param.getNext(), useParam = useParam.getNext()) {
              if (!param.getTypeExpr().subst(substitution).isLessOrEquals(useParam.getTypeExpr(), DummyEquations.getInstance(), null)) {
                ok = false;
                break;
              }
              substitution.add(param, new ReferenceExpression(useParam));
            }
          } else {
            typedDef.setParameters(useParent.getParameters());
            for (DependentLink param = typedDef.getParameters(); param.hasNext(); param = param.getNext()) {
              typechecker.addBinding(null, param);
            }
          }
          if (ok) {
            List<Expression> args = new ArrayList<>();
            for (DependentLink param = typedDef.getParameters(); param.hasNext(); param = param.getNext()) {
              args.add(new ReferenceExpression(param));
            }
            Expression type = useParent.getDefCall(useParent.makeIdLevels(), args);
            Sort sort = type.getSortOfType();
            if (sort != null) {
              int level = ((UniverseExpression) typedDef.getResultType()).getSort().getHLevel().getConstant();
              CountingErrorReporter countingErrorReporter = new CountingErrorReporter(GeneralError.Level.ERROR, errorReporter);
              TypecheckingResult result = typechecker.withErrorReporter(countingErrorReporter, tc ->
                  typechecker.finalize(TypecheckingResult.fromChecked(prover.prove(body.getTerm(), type, CheckTypeVisitor.getLevelExpression(new TypeExpression(type, sort), level), level, def, tc)), def, false));
              if (result == null) {
                if (countingErrorReporter.getErrorsNumber() == 0) {
                  errorReporter.report(new TypecheckingError("Cannot prove level", def));
                }
                typedDef.setResultType(new ErrorExpression());
              } else {
                typedDef.setBody(result.expression);
                typedDef.setResultType(result.type);
              }
            }
          }
        }
      }
    } else if (body instanceof Concrete.ElimFunctionBody) {
      Concrete.ElimFunctionBody elimBody = (Concrete.ElimFunctionBody) body;
      List<DependentLink> elimParams = ElimTypechecking.getEliminatedParameters(elimBody.getEliminatedReferences(), elimBody.getClauses(), typedDef.getParameters(), typechecker);
      clauses = elimParams == null ? null : new PatternTypechecking(errorReporter, PatternTypechecking.Mode.FUNCTION, typechecker, true, null, elimParams).typecheckClauses(elimBody.getClauses(), def.getParameters(), typedDef.getParameters(), expectedType);
      Sort sort = expectedType.getSortOfType();
      Body typedBody = clauses == null ? null : new ElimTypechecking(errorReporter, typechecker.getEquations(), expectedType, PatternTypechecking.Mode.FUNCTION, typeLevel, sort != null ? sort.getHLevel() : Level.INFINITY, kind.isSFunc() && kind != FunctionKind.TYPE, elimBody.getClauses(), def).typecheckElim(clauses, typedDef.getParameters(), elimParams);
      if (typedBody != null) {
        if (myNewDef) {
          typedDef.setBody(typedBody);
          typedDef.addStatus(Definition.TypeCheckingStatus.NO_ERRORS);
        }
        boolean conditionsResult = typedDef.getKind() == CoreFunctionDefinition.Kind.LEMMA || new ConditionsChecking(DummyEquations.getInstance(), errorReporter, def).check(typedBody, clauses, elimBody.getClauses(), typedDef);
        if (myNewDef && !conditionsResult) {
          typedDef.addStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
        }
      } else {
        clauses = null;
      }
    } else if (body instanceof Concrete.CoelimFunctionBody) {
      if (def.getResultType() != null) {
        if (def.getStatus() != Concrete.Status.HAS_ERRORS) {
          Pair<Expression, ClassCallExpression> result = typecheckCoClauses(typedDef, def, kind, body.getCoClauseElements());
          if (result != null) {
            if (myNewDef && !def.isRecursive()) {
              if (kind == FunctionKind.CONS) {
                typedDef.setResultType(result.proj1.getType());
              } else {
                ClassCallExpression resultType = result.proj2;
                boolean hasProperties = false;
                for (ClassField field : resultType.getImplementedHere().keySet()) {
                  if (field.isProperty()) {
                    hasProperties = true;
                    break;
                  }
                }
                if (hasProperties) {
                  Map<ClassField, Expression> resultTypeImpls = new LinkedHashMap<>();
                  resultType = new ClassCallExpression(result.proj2.getDefinition(), result.proj2.getLevels(), resultTypeImpls, Sort.PROP, UniverseKind.NO_UNIVERSES);
                  ExprSubstitution substitution = new ExprSubstitution(result.proj2.getThisBinding(), new ReferenceExpression(resultType.getThisBinding()));
                  for (Map.Entry<ClassField, Expression> entry : result.proj2.getImplementedHere().entrySet()) {
                    if (!entry.getKey().isProperty()) {
                      resultTypeImpls.put(entry.getKey(), entry.getValue().subst(substitution));
                    }
                  }
                  typechecker.fixClassExtSort(resultType, def.getResultType());
                  resultType.updateHasUniverses();
                }
                typedDef.setResultType(resultType);
                if (hasProperties || result.proj2.getNumberOfNotImplementedFields() > 0) {
                  typedDef.setBody(result.proj1);
                  if (hasProperties) typedDef.reallyHideBody();
                }
              }
            }
            consType = result.proj2;
          }
        } else {
          TypecheckingResult result = typechecker.finalCheckExpr(def.getResultType(), Type.OMEGA);
          if (myNewDef && result != null) {
            typedDef.setResultType(result.expression);
            typedDef.addStatus(typechecker.getStatus());
          }
        }
        bodyIsOK = true;
      }
    } else if (body instanceof Concrete.TermFunctionBody) {
      Concrete.Expression bodyTerm = ((Concrete.TermFunctionBody) body).getTerm();
      boolean useExpectedType = !expectedType.isError();
      TypecheckingResult nonFinalResult = typechecker.checkExpr(bodyTerm, useExpectedType ? expectedType : null);
      if (useExpectedType && (kind == FunctionKind.LEMMA || def.getData().getKind() == GlobalReferable.Kind.DEFINED_CONSTRUCTOR || nonFinalResult == null || !nonFinalResult.type.isInstance(ClassCallExpression.class)) && !(expectedType instanceof Type && ((Type) expectedType).isOmega())) {
        if (nonFinalResult == null) {
          nonFinalResult = new TypecheckingResult(null, expectedType);
        } else {
          nonFinalResult.type = expectedType;
        }
      }
      TypecheckingResult termResult = typechecker.finalize(nonFinalResult, bodyTerm, kind == FunctionKind.LEMMA);

      if (termResult != null) {
        if (myNewDef) {
          Expression expr = termResult.expression;
          while (expr instanceof LetExpression) {
            expr = ((LetExpression) expr).getExpression();
          }
          if (expr instanceof NewExpression) {
            ExprSubstitution substitution = new ExprSubstitution();
            expr = termResult.expression;
            while (expr instanceof LetExpression) {
              for (HaveClause clause : ((LetExpression) expr).getClauses()) {
                substitution.add(clause, new ReferenceExpression(new PersistentEvaluatingBinding(clause.getName(), clause.getExpression().subst(substitution))));
              }
              expr = ((LetExpression) expr).getExpression();
            }
            termResult.expression = expr.subst(substitution);
            if (termResult.type instanceof LetExpression) {
              expr = termResult.type;
              while (expr instanceof LetExpression) {
                expr = ((LetExpression) expr).getExpression();
              }
              termResult.type = expr.subst(substitution);
            }
          }

          if (!def.isRecursive()) {
            typedDef.setResultType(termResult.type);
          }
          if (termResult.expression != null) {
            typedDef.setBody(termResult.expression);
          }
        }
        if (termResult.expression instanceof NewExpression && myNewDef && def.getData().getKind() != GlobalReferable.Kind.DEFINED_CONSTRUCTOR && (expectedType.isError() || !typedDef.isSFunc()) && !def.isRecursive()) {
          typedDef.setResultType(((NewExpression) termResult.expression).getType());
        }
      }
    } else {
      throw new IllegalStateException();
    }

    if (typedDef.getKind() == CoreFunctionDefinition.Kind.SFUNC && typedDef.getActualBody() instanceof IntervalElim) {
      errorReporter.report(new TypecheckingError("\\sfunc cannot be defined by pattern matching on the interval", def));
      typedDef.setKind(CoreFunctionDefinition.Kind.FUNC);
    }

    checkCanBeLemma(typedDef, def);

    if (myNewDef) {
      ClassCallExpression typeClassCall = typedDef.getResultType().cast(ClassCallExpression.class);
      if (typeClassCall != null) {
        Map<ClassField, Expression> newImpls = new LinkedHashMap<>();
        ClassCallExpression newClassCall = new ClassCallExpression(typeClassCall.getDefinition(), typeClassCall.getLevels(), newImpls, typeClassCall.getSort(), typeClassCall.getUniverseKind());
        Expression newThisBinding = new ReferenceExpression(newClassCall.getThisBinding());
        boolean allImpl = true;
        for (ClassField field : typeClassCall.getDefinition().getFields()) {
          if (!field.isProperty()) {
            Expression impl = typeClassCall.getAbsImplementationHere(field);
            if (impl != null) {
              newImpls.put(field, impl.subst(typeClassCall.getThisBinding(), newThisBinding));
            } else if (!typeClassCall.getDefinition().isImplemented(field)) {
              allImpl = false;
            }
          }
        }
        if (typedDef.getResultTypeLevel() == null) {
          typedDef.setResultType(newClassCall);
        }
        if (allImpl) {
          bodyIsOK = true;
          if (typedDef.getResultTypeLevel() == null) {
            typedDef.reallyHideBody();
            if (typedDef.getReallyActualBody() instanceof NewExpression && ((NewExpression) typedDef.getReallyActualBody()).getRenewExpression() == null) {
              ClassCallExpression bodyClassCall = ((NewExpression) typedDef.getReallyActualBody()).getClassCall();
              bodyClassCall.getImplementedHere().keySet().removeIf(field -> !field.isProperty());
              if (bodyClassCall.getImplementedHere().isEmpty()) {
                typedDef.setBody(null);
              }
            }
          } else {
            typedDef.setBody(null);
          }
        }
      }

      if (kind != FunctionKind.LEMMA && kind != FunctionKind.LEVEL && typedDef.getBody() instanceof DefCallExpression) {
        Integer level = ((DefCallExpression) typedDef.getBody()).getUseLevel();
        if (level != null) {
          typedDef.addParametersLevel(new ParametersLevel(null, level));
        }
      }

      if (typedDef.getResultType() == null) {
        typedDef.setResultType(new ErrorExpression());
      }

      ElimBody elimBody;
      if (typedDef.getActualBody() instanceof ElimBody) {
        elimBody = (ElimBody) typedDef.getActualBody();
      } else if (typedDef.getActualBody() instanceof IntervalElim) {
        elimBody = ((IntervalElim) typedDef.getActualBody()).getOtherwise();
      } else {
        elimBody = null;
      }

      GoodThisParametersVisitor goodThisParametersVisitor;
      if (elimBody != null) {
        goodThisParametersVisitor = new GoodThisParametersVisitor(typedDef.getGoodThisParameters(), elimBody, DependentLink.Helper.size(typedDef.getParameters()));
      } else {
        goodThisParametersVisitor = new GoodThisParametersVisitor(typedDef.getGoodThisParameters(), typedDef.getParameters());
        goodThisParametersVisitor.visitBody(typedDef.getActualBody(), null);
      }
      typedDef.setGoodThisParameters(goodThisParametersVisitor.getGoodParameters());

      if (new UniverseKindChecker().check(typedDef.getResultType())) {
        typedDef.setUniverseKind(UniverseKind.WITH_UNIVERSES);
      } else {
        UniverseKind universeKind = checkForUniverses(typedDef.getParameters());
        typedDef.setUniverseKind(universeKind == UniverseKind.WITH_UNIVERSES ? UniverseKind.WITH_UNIVERSES : universeKind.max(new UniverseKindChecker().getUniverseKind(typedDef.getBody())));
      }
    }

    if (!checkLevelNow) {
      checkTypeLevel(def, typedDef, true);
    }

    if (kind == FunctionKind.INSTANCE) {
      ClassCallExpression typecheckedResultType = typedDef.getResultType() instanceof ClassCallExpression ? (ClassCallExpression) typedDef.getResultType() : null;
      if (typecheckedResultType != null && !typecheckedResultType.getDefinition().isRecord()) {
        ClassField classifyingField = typecheckedResultType.getDefinition().getClassifyingField();
        Expression classifyingExpr;
        if (classifyingField != null) {
          classifyingExpr = typecheckedResultType.getImplementation(classifyingField, new NewExpression(null, typecheckedResultType));
          Set<SingleDependentLink> params = new LinkedHashSet<>();
          while (classifyingExpr instanceof LamExpression) {
            for (SingleDependentLink link = ((LamExpression) classifyingExpr).getParameters(); link.hasNext(); link = link.getNext()) {
              params.add(link);
            }
            classifyingExpr = ((LamExpression) classifyingExpr).getBody();
          }
          if (classifyingExpr != null) {
            classifyingExpr = classifyingExpr.normalize(NormalizationMode.WHNF);
          }

          if (!(classifyingExpr == null || classifyingExpr instanceof ErrorExpression || classifyingExpr instanceof DataCallExpression || classifyingExpr instanceof ConCallExpression || classifyingExpr instanceof FunCallExpression && ((FunCallExpression) classifyingExpr).getDefinition().getKind() == CoreFunctionDefinition.Kind.TYPE || classifyingExpr instanceof ClassCallExpression || params.isEmpty() && (classifyingExpr instanceof UniverseExpression || classifyingExpr instanceof SigmaExpression || classifyingExpr instanceof PiExpression || classifyingExpr instanceof IntegerExpression))) {
            errorReporter.report(new TypecheckingError("Classifying field must be either a universe, a sigma type, a record, or a partially applied data or constructor", def.getResultType() == null ? def : def.getResultType()));
          }
        } else {
          classifyingExpr = null;
        }

        int index = 0;
        for (DependentLink link = typedDef.getParameters(); link.hasNext(); link = link.getNext()) {
          if (link instanceof TypedDependentLink && typedDef.getTypeClassParameterKind(index) == Definition.TypeClassParameterKind.YES) {
            Expression type = link.getTypeExpr();
            if (type instanceof ClassCallExpression && !((ClassCallExpression) type).getDefinition().isRecord()) {
              ClassCallExpression classCall = (ClassCallExpression) type;
              ClassField paramClassifyingField = classCall.getDefinition().getClassifyingField();
              ReferenceExpression refExpr = new ReferenceExpression(link);
              Expression classifyingImpl = null;
              Expression classifyingExprType = null;
              if (paramClassifyingField != null) {
                Levels fieldLevels = classCall.getLevels(paramClassifyingField.getParentClass());
                classifyingImpl = classCall.getImplementation(paramClassifyingField, refExpr);
                if (classifyingImpl == null) {
                  classifyingImpl = FieldCallExpression.make(paramClassifyingField, fieldLevels, refExpr);
                }
                classifyingExprType = classCall.getDefinition().getFieldType(paramClassifyingField, fieldLevels, refExpr);
              }
              if (classifyingImpl == null || classifyingExpr == null || compareExpressions(classifyingImpl, classifyingExpr, classifyingExprType) != -1) {
                typedDef.setTypeClassParameter(index, Definition.TypeClassParameterKind.ONLY_LOCAL);
              }
            }
          }
          index++;
        }
      }
    } else if (kind == FunctionKind.TYPE) {
      if (!(typedDef.getResultType() instanceof UniverseExpression)) {
        if (!typedDef.getResultType().reportIfError(errorReporter, def.getResultType())) {
          errorReporter.report(new TypeMismatchError(new UniverseExpression(Sort.STD), typedDef.getResultType(), def.getResultType()));
        }
        typedDef.setKind(CoreFunctionDefinition.Kind.SFUNC);
      }
    } else if (myNewDef && def instanceof Concrete.CoClauseFunctionDefinition && def.getKind() == FunctionKind.CLASS_COCLAUSE) {
      Referable fieldRef = ((Concrete.CoClauseFunctionDefinition) def).getImplementedField();
      Definition fieldDef = fieldRef instanceof TCDefReferable ? ((TCDefReferable) fieldRef).getTypechecked() : null;
      Definition useParent = def.getUseParent().getTypechecked();
      if (fieldDef instanceof ClassField && useParent instanceof ClassDefinition) {
        Map<ClassField, Expression> defaultImpl = new LinkedHashMap<>();
        ClassDefinition classDef = (ClassDefinition) useParent;
        Levels levels = classDef.makeIdLevels();
        ClassCallExpression thisType = new ClassCallExpression(classDef, levels, defaultImpl, classDef.getSort(), classDef.getUniverseKind());
        for (ClassField field : classDef.getFields()) {
          AbsExpression defaultExpr = classDef.getDefault(field);
          if (defaultExpr != null) {
            defaultImpl.put(field, defaultExpr.apply(new ReferenceExpression(thisType.getThisBinding()), LevelSubstitution.EMPTY));
          }
        }
        TypedSingleDependentLink thisBinding = new TypedSingleDependentLink(false, "this", thisType, true);
        thisType.setSort(classDef.computeSort(levels, defaultImpl, thisBinding));
        thisType.updateHasUniverses();
        Expression result = DefCallResult.makeTResult(new Concrete.ReferenceExpression(def.getData().getData(), def.getData()), typedDef, typedDef.makeIdLevels()).applyExpression(new ReferenceExpression(thisBinding), false, typechecker, def).toResult(typechecker).expression;
        Expression actualType = result.getType();
        Expression fieldType = ((ClassField) fieldDef).getType().applyExpression(new ReferenceExpression(thisBinding));
        if (actualType.isLessOrEquals(fieldType, DummyEquations.getInstance(), def)) {
          classDef.addDefault((ClassField) fieldDef, new AbsExpression(thisBinding, result));
        } else {
          errorReporter.report(new TypeMismatchError(fieldType, actualType, def));
        }
      }
    }

    if (typedDef instanceof DConstructor) {
      Set<DependentLink> usedVars = new HashSet<>();
      ExpressionPattern pattern = null;
      if (body instanceof Concrete.TermFunctionBody) {
        if (typedDef.getBody() instanceof NewExpression && typedDef.getResultType() instanceof ClassCallExpression) {
          pattern = checkDConstructor((ClassCallExpression) typedDef.getResultType(), (NewExpression) typedDef.getBody(), usedVars, def);
        } else if (typedDef.getBody() instanceof Expression) {
          pattern = checkDConstructor((Expression) typedDef.getBody(), usedVars, body.getTerm());
        }
      } else if (body instanceof Concrete.CoelimFunctionBody) {
        if (consType != null && typedDef.getResultType() instanceof ClassCallExpression && ((ClassCallExpression) typedDef.getResultType()).getNumberOfNotImplementedFields() == 0) {
          pattern = checkDConstructor(consType, new NewExpression(null, (ClassCallExpression) typedDef.getResultType()), usedVars, def);
        }
      } else {
        errorReporter.report(new TypecheckingError("\\cons cannot be defined by pattern matching", def));
      }

      if (pattern != null) {
        int numberOfParameters = 0;
        for (DependentLink link = typedDef.getParameters(); link.hasNext(); link = link.getNext()) {
          if (usedVars.contains(link)) {
            break;
          }
          numberOfParameters++;
        }

        for (DependentLink link = DependentLink.Helper.get(typedDef.getParameters(), numberOfParameters); link.hasNext(); link = link.getNext()) {
          if (!usedVars.contains(link)) {
            errorReporter.report(new TypecheckingError("Parameters of \\cons that do not occur in patterns must be listed before other parameters", def));
            pattern = null;
            break;
          }
        }

        if (pattern != null) {
          DependentLink link = typedDef.getParameters();
          for (int i = 0; i < numberOfParameters; i++) {
            if (link.isExplicit()) {
              errorReporter.report(new TypecheckingError("Parameters of \\cons that do not occur in patterns must be implicit", def));
              pattern = null;
              break;
            }
            if (!typedDef.getResultType().findBinding(link)) {
              if (!typedDef.getResultType().reportIfError(errorReporter, def)) {
                errorReporter.report(new TypecheckingError("Parameters of \\cons that do not occur in patterns must occur in the result type", def));
              }
              pattern = null;
              break;
            }
            link = link.getNext();
          }
        }

        if (myNewDef && pattern != null) {
          ((DConstructor) typedDef).setPattern(pattern);
          ((DConstructor) typedDef).setNumberOfParameters(numberOfParameters);
        }
      }
    }

    if (myNewDef) {
      typechecker.setStatus(def.getStatus().getTypecheckingStatus());
      typedDef.addStatus(typechecker.getStatus().max(!bodyIsOK && typedDef.getActualBody() == null ? Definition.TypeCheckingStatus.HAS_ERRORS : Definition.TypeCheckingStatus.NO_ERRORS));
      def.setTypechecked();
    }

    return clauses;
  }

  private void typecheckDataHeader(DataDefinition dataDefinition, Concrete.DataDefinition def, LocalInstancePool localInstancePool) {
    def.getData().setTypecheckedIfNotCancelled(dataDefinition);
    if (def.enclosingClass != null) {
      dataDefinition.setHasEnclosingClass(true);
    }
    if (myNewDef) {
      dataDefinition.setLevelParameters(typecheckLevelParameters(def));
    }

    LinkList list = new LinkList();
    Sort userSort = null;
    boolean paramsOk = typecheckParameters(def, dataDefinition, list, localInstancePool, null, myNewDef ? null : dataDefinition.getParameters(), null) != null;
    checkNoStrictParameters(def.getParameters());

    if (def.getUniverse() != null) {
      Type userTypeResult = typechecker.finalCheckType(def.getUniverse(), Type.OMEGA, false);
      if (userTypeResult != null) {
        userSort = userTypeResult.getExpr().toSort();
        if (userSort == null) {
          errorReporter.report(new TypecheckingError("Expected a universe", def.getUniverse()));
        }
      }
    }

    if (!myNewDef) {
      return;
    }

    dataDefinition.setSort(userSort);
    calculateTypeClassParameters(def, dataDefinition);
    calculateParametersTypecheckingOrder(dataDefinition);

    if (!paramsOk) {
      for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
        for (Concrete.Constructor constructor : clause.getConstructors()) {
          constructor.getData().setTypechecked(new Constructor(constructor.getData(), dataDefinition));
        }
      }
    }

    dataDefinition.setGoodThisParameters(new GoodThisParametersVisitor(dataDefinition.getParameters()).getGoodParameters());
  }

  private boolean typecheckDataBody(DataDefinition dataDefinition, Concrete.DataDefinition def, Set<DataDefinition> dataDefinitions) {
    if (myNewDef) {
      dataDefinition.setUniverseKind(UniverseKind.WITH_UNIVERSES);
      dataDefinition.getConstructors().clear();
    }

    Sort userSort = dataDefinition.getSort();
    Sort inferredSort = def.getConstructorClauses().isEmpty() ? Sort.PROP : Sort.generateInferVars(typechecker.getEquations(), false, def);
    if (myNewDef) {
      dataDefinition.setSort(inferredSort);
    }

    boolean dataOk = true;
    List<DependentLink> elimParams = Collections.emptyList();
    if (def.getEliminatedReferences() != null) {
      elimParams = ElimTypechecking.getEliminatedParameters(def.getEliminatedReferences(), def.getConstructorClauses(), dataDefinition.getParameters(), typechecker);
      if (elimParams == null) {
        dataOk = false;
      }
    }

    ErrorReporter originalErrorReporter = errorReporter;
    ErrorReporterCounter countingErrorReporter = new ErrorReporterCounter(GeneralError.Level.ERROR, originalErrorReporter);
    errorReporter = countingErrorReporter;

    if (!def.getConstructorClauses().isEmpty()) {
      Map<Referable, Binding> context = typechecker.getContext();
      PatternTypechecking dataPatternTypechecking = elimParams == null ? null : new PatternTypechecking(errorReporter, PatternTypechecking.Mode.DATA, typechecker, true, null, elimParams);

      Set<TCReferable> notAllowedConstructors = new HashSet<>();
      for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
        for (Concrete.Constructor constructor : clause.getConstructors()) {
          notAllowedConstructors.add(constructor.getData());
        }
      }

      LocalInstancePool instancePool = typechecker.getInstancePool().getLocalInstancePool();
      for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
        typechecker.copyContextFrom(context);

        // Typecheck patterns and compute free bindings
        boolean patternsOK = true;
        PatternTypechecking.Result result = null;
        if (clause.getPatterns() != null) {
          if (def.getEliminatedReferences() == null) {
            originalErrorReporter.report(new TypecheckingError("Expected a constructor without patterns", clause));
            dataOk = false;
          }
          if (dataPatternTypechecking != null) {
            ExprSubstitution substitution = new ExprSubstitution();
            result = dataPatternTypechecking.typecheckPatterns(clause.getPatterns(), def.getParameters(), dataDefinition.getParameters(), substitution, null, def);
            if (instancePool != null) {
              typechecker.getInstancePool().setInstancePool(instancePool.subst(substitution));
            }
            if (result != null && result.hasEmptyPattern()) {
              originalErrorReporter.report(new RedundantClauseError(clause));
              result = null;
            }
            if (result == null) {
              typechecker.copyContextFrom(context);
              patternsOK = false;
            }
          }
        } else {
          if (def.getEliminatedReferences() != null) {
            originalErrorReporter.report(new TypecheckingError("Expected constructors with patterns", clause));
            dataOk = false;
          }
        }

        // Process constructors
        for (Concrete.Constructor constructor : clause.getConstructors()) {
          // Check that constructors do not refer to constructors defined later
          FreeReferablesVisitor visitor = new FreeReferablesVisitor(notAllowedConstructors);
          if (constructor.getResultType() != null) {
            if (constructor.getResultType().accept(visitor, null) != null) {
              errorReporter.report(new ConstructorReferenceError(constructor.getResultType()));
              constructor.setResultType(null);
            }
          }
          Iterator<Concrete.FunctionClause> it = constructor.getClauses().iterator();
          while (it.hasNext()) {
            Concrete.FunctionClause conClause = it.next();
            if (visitor.visitClause(conClause, null) != null) {
              errorReporter.report(new ConstructorReferenceError(conClause));
              it.remove();
            }
          }
          boolean constructorOK = patternsOK;
          if (visitor.visitParameters(constructor.getParameters(), null) != null) {
            errorReporter.report(new ConstructorReferenceError(constructor));
            constructorOK = false;
          }
          if (!constructorOK) {
            constructor.getParameters().clear();
            constructor.getEliminatedReferences().clear();
            constructor.getClauses().clear();
            constructor.setResultType(null);
          }
          notAllowedConstructors.remove(constructor.getData());

          // Typecheck constructors
          List<ExpressionPattern> patterns = result == null ? null : result.getPatterns();
          if (!typecheckConstructor(constructor, patterns, dataDefinition, dataDefinitions)) {
            dataOk = false;
          }
        }
      }
      typechecker.getInstancePool().setInstancePool(instancePool);

      if (inferredSort.isProp() || inferredSort.getHLevel().isVarOnly()) {
        boolean ok = true;
        for (int i = 0; i < dataDefinition.getConstructors().size(); i++) {
          List<ExpressionPattern> patterns1 = dataDefinition.getConstructors().get(i).getPatterns();
          for (int j = i + 1; j < dataDefinition.getConstructors().size(); j++) {
            List<ExpressionPattern> patterns2 = dataDefinition.getConstructors().get(j).getPatterns();
            if (patterns1 == null || patterns2 == null || ExpressionPattern.unify(patterns1, patterns2, null, null, null, errorReporter, def)) {
              ok = false;
              break;
            }
          }
          if (!ok) {
            break;
          }
        }
        if (!ok) {
          Sort.compare(Sort.SET0, inferredSort, CMP.LE, typechecker.getEquations(), def);
        }
      }

      // Check if constructors pattern match on the interval
      for (Constructor constructor : dataDefinition.getConstructors()) {
        if (constructor.getBody() instanceof IntervalElim && !inferredSort.getHLevel().isInfinity()) {
          inferredSort = new Sort(inferredSort.getPLevel(), Level.INFINITY);
        }
      }

      typechecker.invokeDeferredMetas(null, null, false);
      LevelEquationsSolver levelSolver = typechecker.getEquations().makeLevelEquationsSolver();
      LevelSubstitution levelSubstitution = levelSolver.solveLevels();
      typechecker.getEquations().finalizeEquations(levelSubstitution, def);
      InPlaceLevelSubstVisitor substVisitor = new InPlaceLevelSubstVisitor(levelSubstitution);
      StripVisitor stripVisitor = new StripVisitor(errorReporter);
      typechecker.invokeDeferredMetas(substVisitor, stripVisitor, true);
      for (Constructor constructor : dataDefinition.getConstructors()) {
        if (!substVisitor.isEmpty()) {
          substVisitor.visitParameters(constructor.getParameters(), null);
          substVisitor.visitBody(constructor.getBody(), null);
        }
        stripVisitor.visitParameters(constructor.getParameters());
        stripVisitor.visitBody(constructor.getBody());
      }
      if (!substVisitor.isEmpty()) {
        inferredSort = inferredSort.subst(substVisitor.getLevelSubstitution());
        dataDefinition.setSort(inferredSort);
      }
    }
    if (myNewDef && !dataOk) {
      dataDefinition.addStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
    }

    errorReporter = originalErrorReporter;

    // Find covariant parameters
    if (myNewDef && dataDefinition.getParameters().hasNext()) {
      int index = 0;
      Set<DependentLink> parameters = new HashSet<>();
      for (DependentLink link = dataDefinition.getParameters(); link.hasNext(); link = link.getNext(), index++) {
        dataDefinition.setCovariant(index, true);
        parameters.add(link);
      }

      int size;
      do {
        size = parameters.size();
        getCovariantParameters(dataDefinition, parameters);

        index = 0;
        for (DependentLink link = dataDefinition.getParameters(); link.hasNext(); link = link.getNext(), index++) {
          dataDefinition.setCovariant(index, parameters.contains(link));
        }
      } while (!parameters.isEmpty() && parameters.size() != size);
    }

    // Check truncatedness
    if (def.isTruncated()) {
      if (userSort == null) {
        originalErrorReporter.report(new CertainTypecheckingError(CertainTypecheckingError.Kind.TRUNCATED_WITHOUT_UNIVERSE, def));
      } else {
        if (inferredSort.isLessOrEquals(userSort)) {
          originalErrorReporter.report(new CertainTypecheckingError(CertainTypecheckingError.Kind.DATA_WONT_BE_TRUNCATED, def.getUniverse() == null ? def : def.getUniverse()));
        } else if (myNewDef) {
          dataDefinition.setTruncatedLevel(userSort.getHLevel().getConstant());
          dataDefinition.setSquashed(true);
        }
      }
    } else if (countingErrorReporter.getErrorsNumber() == 0 && userSort != null && !inferredSort.isLessOrEquals(userSort)) {
      countingErrorReporter.report(new DataUniverseError(inferredSort, userSort, def.getUniverse() == null ? def : def.getUniverse()));
    }

    if (myNewDef) {
      dataDefinition.setSort(countingErrorReporter.getErrorsNumber() == 0 && userSort != null ? userSort : inferredSort);
      typechecker.setStatus(def.getStatus().getTypecheckingStatus());
      dataDefinition.addStatus(typechecker.getStatus());

      UniverseKind kind = checkForUniverses(dataDefinition.getParameters());
      if (kind != UniverseKind.WITH_UNIVERSES) {
        dataDefinition.setUniverseKind(kind);
        loop:
        for (Constructor constructor : dataDefinition.getConstructors()) {
          for (DependentLink link = constructor.getParameters(); link.hasNext(); link = link.getNext()) {
            link = link.getNextTyped(null);
            kind = kind.max(new UniverseKindChecker().getUniverseKind(link.getTypeExpr()));
            if (kind == UniverseKind.WITH_UNIVERSES) {
              break loop;
            }
          }
        }
      }
      dataDefinition.setUniverseKind(kind);
    }

    if (myNewDef) {
      GoodThisParametersVisitor goodThisParametersVisitor = new GoodThisParametersVisitor(dataDefinition.getGoodThisParameters(), dataDefinition.getParameters());
      for (Constructor constructor : dataDefinition.getConstructors()) {
        goodThisParametersVisitor.visitParameters(constructor.getParameters(), null);
        goodThisParametersVisitor.visitBody(constructor.getBody(), null);
      }
      dataDefinition.setGoodThisParameters(goodThisParametersVisitor.getGoodParameters());
      def.setTypechecked();
    }

    return countingErrorReporter.getErrorsNumber() == 0;
  }

  private Expression normalizePathExpression(Expression type, Constructor constructor, Concrete.SourceNode sourceNode) {
    type = type.normalize(NormalizationMode.WHNF);
    if (type instanceof DataCallExpression && ((DataCallExpression) type).getDefinition() == Prelude.PATH) {
      List<Expression> pathArgs = ((DataCallExpression) type).getDefCallArguments();
      Expression lamExpr = pathArgs.get(0).normalize(NormalizationMode.WHNF);
      if (lamExpr instanceof LamExpression) {
        Expression newType = normalizePathExpression(((LamExpression) lamExpr).getBody(), constructor, sourceNode);
        if (newType == null) {
          return null;
        } else {
          List<Expression> args = new ArrayList<>(3);
          args.add(new LamExpression(((LamExpression) lamExpr).getResultSort(), ((LamExpression) lamExpr).getParameters(), newType));
          args.add(pathArgs.get(1));
          args.add(pathArgs.get(2));
          return new DataCallExpression(Prelude.PATH, ((DataCallExpression) type).getLevels(), args);
        }
      } else {
        type = null;
      }
    }

    Expression expectedType = constructor.getDataTypeExpression(constructor.makeIdLevels());
    if (type == null || !Expression.compare(type, expectedType, Type.OMEGA, CMP.EQ)) {
      errorReporter.report(new TypecheckingError("Expected an iterated path type in " + expectedType, sourceNode));
      return null;
    }

    return type;
  }

  private Expression addAts(Expression expression, DependentLink param, Expression type) {
    while (type instanceof DataCallExpression && ((DataCallExpression) type).getDefinition() == Prelude.PATH) {
      expression = AtExpression.make(expression, new ReferenceExpression(param), false);
      type = ((LamExpression) ((DataCallExpression) type).getDefCallArguments().get(0)).getBody();
      param = param.getNext();
    }
    return expression;
  }

  private boolean checkNoConstructors(Expression expr, DataDefinition dataDefinition) {
    return !expr.accept(new SearchVisitor<Void>() {
      @Override
      protected CoreExpression.FindAction processDefCall(DefCallExpression expression, Void param) {
        return expression instanceof ConCallExpression && ((ConCallExpression) expression).getDefinition().getDataType() == dataDefinition ? CoreExpression.FindAction.STOP : CoreExpression.FindAction.CONTINUE;
      }
    }, null);
  }

  private boolean checkConstructorsOnlyOnTop(Expression expr, DataDefinition dataDefinition) {
    if (expr instanceof ConCallExpression && ((ConCallExpression) expr).getDefinition().getDataType() == dataDefinition) {
      for (Expression argument : ((ConCallExpression) expr).getDataTypeArguments()) {
        if (!checkConstructorsOnlyOnTop(argument, dataDefinition)) return false;
      }
      for (Expression argument : ((ConCallExpression) expr).getDefCallArguments()) {
        if (!checkConstructorsOnlyOnTop(argument, dataDefinition)) return false;
      }
      return true;
    } else if (expr instanceof LamExpression) {
      return checkNoConstructors(((LamExpression) expr).getParameters().getTypeExpr(), dataDefinition) && checkConstructorsOnlyOnTop(((LamExpression) expr).getBody(), dataDefinition);
    } else if (expr instanceof TupleExpression) {
      if (!checkNoConstructors(((TupleExpression) expr).getSigmaType(), dataDefinition)) return false;
      for (Expression field : ((TupleExpression) expr).getFields()) {
        if (!checkConstructorsOnlyOnTop(field, dataDefinition)) return false;
      }
      return true;
    } else if (expr instanceof NewExpression) {
      NewExpression newExpr = (NewExpression) expr;
      if (newExpr.getRenewExpression() != null && !checkConstructorsOnlyOnTop(newExpr.getRenewExpression(), dataDefinition)) return false;
      for (Expression impl : newExpr.getClassCall().getImplementedHere().values()) {
        if (!checkConstructorsOnlyOnTop(impl, dataDefinition)) return false;
      }
      return true;
    }

    return checkNoConstructors(expr, dataDefinition);
  }

  private void checkConstructorsOnlyOnTop(Expression expr, DataDefinition dataDefinition, Concrete.SourceNode sourceNode) {
    if (expr != null && !checkConstructorsOnlyOnTop(expr, dataDefinition)) {
      errorReporter.report(new TypecheckingError("Constructors can occur only on the top level of conditions", sourceNode));
    }
  }

  private boolean typecheckConstructor(Concrete.Constructor def, List<ExpressionPattern> patterns, DataDefinition dataDefinition, Set<DataDefinition> dataDefinitions) {
    Constructor constructor = myNewDef ? new Constructor(def.getData(), dataDefinition) : null;
    if (constructor != null) {
      constructor.setPatterns(patterns);
    }
    Constructor oldConstructor = constructor != null ? constructor : (Constructor) def.getData().getTypechecked();

    List<DependentLink> elimParams = null;
    Expression constructorType = null;
    LinkList list = new LinkList();
    boolean ok;

    try (var ignored = new Utils.SetContextSaver<>(typechecker.getContext())) {
      if (constructor != null) {
        def.getData().setTypechecked(constructor);
        dataDefinition.addConstructor(constructor);
      }

      ok = typecheckParameters(def, constructor, list, null, dataDefinition.getSort(), myNewDef ? null : oldConstructor.getParameters(), null) != null;
      if (constructor != null) {
        constructor.setStrictParameters(getStrictParameters(def.getParameters()));
      }

      int i = 0;
      for (DependentLink link = list.getFirst(); link.hasNext(); link = link.getNext(), i++) {
        link = link.getNextTyped(null);
        if (new RecursiveDataChecker(dataDefinitions, errorReporter, def, def.getParameters().get(i)).check(link.getTypeExpr())) {
          if (constructor != null) {
            constructor.setParameters(EmptyDependentLink.getInstance());
          }
          return false;
        }
      }

      if (def.getResultType() != null) {
        Type resultType = typechecker.checkType(def.getResultType(), Type.OMEGA);
        if (resultType != null) {
          constructorType = normalizePathExpression(resultType.getExpr(), oldConstructor, def.getResultType());
        }
        def.setResultType(null);
      }

      if (!def.getClauses().isEmpty()) {
        elimParams = ElimTypechecking.getEliminatedParameters(def.getEliminatedReferences(), def.getClauses(), list.getFirst(), typechecker);
      }
    }

    if (constructor != null && def.isCoerce()) {
      dataDefinition.getCoerceData().addCoercingConstructor(constructor, errorReporter, def);
    }

    if (elimParams != null) {
      try (var ignored = new Utils.SetContextSaver<>(typechecker.getContext())) {
        Expression expectedType = oldConstructor.getDataTypeExpression(oldConstructor.makeIdLevels());
        PatternTypechecking patternTypechecking = new PatternTypechecking(errorReporter, PatternTypechecking.Mode.CONSTRUCTOR, typechecker, false, null, elimParams);
        List<ExtElimClause> clauses = patternTypechecking.typecheckClauses(def.getClauses(), def.getParameters(), oldConstructor.getParameters(), expectedType);
        if (clauses != null) {
          for (int i = 0; i < clauses.size(); i++) {
            checkConstructorsOnlyOnTop(clauses.get(i).getExpression(), dataDefinition, def.getClauses().get(i).getExpression());
          }
        }
        Body body = clauses == null ? null : new ElimTypechecking(errorReporter, typechecker.getEquations(), expectedType, PatternTypechecking.Mode.CONSTRUCTOR, def.getClauses(), def).typecheckElim(clauses, oldConstructor.getParameters(), elimParams);
        if (constructor != null) {
          constructor.setBody(body);
          constructor.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
        }

        boolean dataSortIsProp = dataDefinition.getSort().isProp();
        if (dataSortIsProp) {
          dataDefinition.setSort(Sort.SET0);
        }
        if (body != null) {
          new ConditionsChecking(DummyEquations.getInstance(), errorReporter, def).check(body, clauses, def.getClauses(), oldConstructor);
        }
        if (dataSortIsProp) {
          dataDefinition.setSort(Sort.PROP);
        }
      }
    }

    if (constructor != null && constructorType != null) {
      int numberOfNewParameters = 0;
      for (Expression type = constructorType; type instanceof DataCallExpression && ((DataCallExpression) type).getDefinition() == Prelude.PATH; type = ((LamExpression) ((DataCallExpression) type).getDefCallArguments().get(0)).getBody()) {
        numberOfNewParameters++;
      }

      if (numberOfNewParameters != 0) {
        DependentLink newParam = new TypedDependentLink(true, "i" + numberOfNewParameters, Interval(), EmptyDependentLink.getInstance());
        for (int i = numberOfNewParameters - 1; i >= 1; i--) {
          newParam = new UntypedDependentLink("i" + i, newParam);
        }
        list.append(newParam);
        constructor.setParameters(list.getFirst());

        List<IntervalElim.CasePair> pairs;
        ElimBody elimBody;
        if (constructor.getBody() instanceof IntervalElim) {
          pairs = ((IntervalElim) constructor.getBody()).getCases();
          for (int i = 0; i < pairs.size(); i++) {
            pairs.set(i, new IntervalElim.CasePair(addAts(pairs.get(i).proj1, newParam, constructorType), addAts(pairs.get(i).proj2, newParam, constructorType)));
          }
          elimBody = ((IntervalElim) constructor.getBody()).getOtherwise();
        } else {
          pairs = new ArrayList<>();
          elimBody = constructor.getBody() instanceof ElimBody ? (ElimBody) constructor.getBody() : null;
        }

        while (constructorType instanceof DataCallExpression && ((DataCallExpression) constructorType).getDefinition() == Prelude.PATH) {
          List<Expression> pathArgs = ((DataCallExpression) constructorType).getDefCallArguments();
          LamExpression lamExpr = (LamExpression) pathArgs.get(0);
          constructorType = lamExpr.getBody();
          pairs.add(new IntervalElim.CasePair(addAts(pathArgs.get(1), newParam, constructorType.subst(lamExpr.getParameters(), Left())), addAts(pathArgs.get(2), newParam, constructorType.subst(lamExpr.getParameters(), Right()))));
          constructorType = constructorType.subst(lamExpr.getParameters(), new ReferenceExpression(newParam));
          newParam = newParam.getNext();
        }

        constructor.setBody(new IntervalElim(DependentLink.Helper.size(list.getFirst()), pairs, elimBody));
      }
    }

    if (constructor != null) {
      constructor.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
      calculateTypeClassParameters(def, constructor);
      calculateGoodThisParameters(constructor);
      calculateParametersTypecheckingOrder(constructor);

      int recursiveIndex = -1;
      int i = 0;
      loop:
      for (DependentLink link = constructor.getParameters(); link.hasNext(); link = link.getNext(), i++) {
        if (link.getTypeExpr() instanceof DataCallExpression && dataDefinitions.contains(((DataCallExpression) link.getTypeExpr()).getDefinition())) {
          for (DependentLink link2 = link.getNext(); link2.hasNext(); link2 = link2.getNext()) {
            link2 = link2.getNextTyped(null);
            if (link2.getTypeExpr().findFreeBinding(link)) {
              continue loop;
            }
          }

          if (recursiveIndex != -1) {
            recursiveIndex = -1;
            break;
          } else {
            recursiveIndex = i;
          }
        }
      }

      if (recursiveIndex != -1) {
        constructor.setRecursiveParameter(recursiveIndex);
      }
    }
    return ok;
  }

  private static class LocalInstance {
    final ClassCallExpression classCall;
    final ClassField instanceField;

    LocalInstance(ClassCallExpression classCall, ClassField instanceField) {
      this.classCall = classCall;
      this.instanceField = instanceField;
    }
  }

  private ClassField findClassifyingField(ClassDefinition superClass, ClassDefinition classDef, Set<ClassDefinition> visited) {
    if (!visited.add(superClass)) {
      return null;
    }

    ClassField field = superClass.getClassifyingField();
    if (field == null) {
      return null;
    }

    if (!classDef.isImplemented(field)) {
      return field;
    }

    for (ClassDefinition superSuperClass : superClass.getSuperClasses()) {
      ClassField field1 = findClassifyingField(superSuperClass, classDef, visited);
      if (field1 != null) {
        return field1;
      }
    }

    return null;
  }

  private void typecheckLevelFields(ClassDefinition typedDef, Concrete.ClassDefinition def, LevelVariable base, List<LevelVariable> levelFields) {
    int i = 0;
    for (ClassDefinition superClass : typedDef.getSuperClasses()) {
      Concrete.ReferenceExpression aSuperClass = def.getSuperClasses().get(i);
      if (base == LevelVariable.PVAR && (aSuperClass.getPLevels() != null || !superClass.hasPLevelParameters()) || base == LevelVariable.HVAR && (aSuperClass.getHLevels() != null || !superClass.hasHLevelParameters())) {
        continue;
      }
      List<? extends LevelVariable> superLevelFields = superClass.getLevelParameters();
      if (superLevelFields == null) {
        levelFields.add(base);
      } else {
        int order = 0;
        for (LevelVariable superLevelField : superLevelFields) {
          if (superLevelField.getType() == base.getType()) {
            levelFields.add(superLevelField instanceof FieldLevelVariable ? new FieldLevelVariable(base.getType(), superLevelField.getName(), order++, ((FieldLevelVariable) superLevelField).getSize(), ((FieldLevelVariable) superLevelField).getLevelField()) : superLevelField);
          }
        }
      }
      return;
    }
    levelFields.add(base);
  }

  private void typecheckClassLevelParameters(Concrete.LevelParameters params, LevelVariable.LvlType type, Map<LevelReferable, ParamLevelVariable> result, List<LevelVariable> parameters, TCDefReferable superClass) {
    ClassDefinition superClassDef = superClass != null && superClass.getTypechecked() instanceof ClassDefinition ? (ClassDefinition) superClass.getTypechecked() : null;
    List<? extends LevelVariable> levelParams = superClassDef == null || superClassDef.getLevelParameters() == null ? null : superClassDef.getLevelParameters();
    if (levelParams != null) {
      levelParams = type == LevelVariable.LvlType.PLVL ? levelParams.subList(0, superClassDef.getNumberOfPLevelParameters()) : levelParams.subList(superClassDef.getNumberOfPLevelParameters(), levelParams.size());
    }
    for (int i = 0; i < params.referables.size(); i++) {
      LevelReferable ref = params.referables.get(i);
      FieldLevelVariable.LevelField originalField = levelParams != null && i < levelParams.size() && levelParams.get(i) instanceof FieldLevelVariable ? ((FieldLevelVariable) levelParams.get(i)).getLevelField() : null;
      FieldLevelVariable var = new FieldLevelVariable(type, ref.getRefName(), i, params.isIncreasing ? i : params.referables.size() - 1 - i, originalField != null ? originalField : new FieldLevelVariable.LevelField());
      result.put(ref, var);
      parameters.add(var);
    }
  }

  private boolean compareLevelFields(LevelVariable levelField1, LevelVariable levelField2) {
    return (levelField1 instanceof FieldLevelVariable) == (levelField2 instanceof FieldLevelVariable) && (!(levelField1 instanceof FieldLevelVariable) || ((FieldLevelVariable) levelField1).getLevelField().equals(((FieldLevelVariable) levelField2).getLevelField()));
  }

  private boolean compareLevelFields(List<? extends LevelVariable> levelFields1, List<? extends LevelVariable> levelFields2, Concrete.ReferenceExpression superClass2, LevelVariable base) {
    if (levelFields1 == null && levelFields2 == null || levelFields2 != null && levelFields2.isEmpty()) return true;
    if (levelFields1 == null) {
      levelFields1 = Collections.singletonList(base);
    } else if (levelFields2 == null) {
      levelFields2 = Collections.singletonList(base);
    }
    boolean ok = levelFields1.size() == levelFields2.size();
    if (ok) {
      for (int i = 0; i < levelFields1.size(); i++) {
        if (!compareLevelFields(levelFields1.get(i), levelFields2.get(i))) {
          ok = false;
          break;
        }
      }
    }
    if (!ok && (base == LevelVariable.PVAR ? superClass2.getPLevels() : superClass2.getHLevels()) == null) {
      errorReporter.report(new TypecheckingError(GeneralError.Level.WARNING, "The level parameters of the super class do not match the level parameters of the class", superClass2));
    }
    return ok;
  }

  private boolean compareLevelFields(ClassDefinition classDef1, ClassDefinition classDef2, Concrete.ReferenceExpression superClass) {
    List<? extends LevelVariable> levelParams1 = classDef1.getLevelParameters();
    List<? extends LevelVariable> levelParams2 = classDef2.getLevelParameters();
    return compareLevelFields(levelParams1 == null ? null : levelParams1.subList(0, classDef1.getNumberOfPLevelParameters()), levelParams2 == null ? null : levelParams2.subList(0, classDef2.getNumberOfPLevelParameters()), superClass, LevelVariable.PVAR)
        && compareLevelFields(levelParams1 == null ? null : levelParams1.subList(classDef1.getNumberOfPLevelParameters(), levelParams1.size()), levelParams2 == null ? null : levelParams2.subList(classDef2.getNumberOfPLevelParameters(), levelParams2.size()), superClass, LevelVariable.HVAR);
  }

  private void typecheckClass(Concrete.ClassDefinition def, ClassDefinition typedDef) {
    if (myNewDef) {
      typedDef.clear();
      typedDef.setUniverseKind(UniverseKind.WITH_UNIVERSES);
    }

    boolean classOk = true;

    if (myNewDef) {
      typedDef.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
    }

    // Process super classes
    for (Concrete.ReferenceExpression aSuperClass : def.getSuperClasses()) {
      ClassDefinition superClass = typechecker.referableToDefinition(aSuperClass.getReferent(), ClassDefinition.class, "Expected a class", aSuperClass);
      if (superClass == null) {
        continue;
      }
      if (superClass == Prelude.DEP_ARRAY) {
        errorReporter.report(new TypecheckingError("Array cannot be extended", aSuperClass));
        continue;
      }

      if (myNewDef) {
        typedDef.addFields(superClass.getFields());
        typedDef.addSuperClass(superClass);
      }
    }

    // Set level fields
    {
      List<LevelVariable> levelFields = new ArrayList<>();
      Map<LevelReferable, ParamLevelVariable> variables = new HashMap<>();
      if (def.getPLevelParameters() != null) {
        typecheckClassLevelParameters(def.getPLevelParameters(), LevelVariable.LvlType.PLVL, variables, levelFields, def.pOriginalDef);
      } else {
        typecheckLevelFields(typedDef, def, LevelVariable.PVAR, levelFields);
      }
      if (def.getHLevelParameters() != null) {
        typecheckClassLevelParameters(def.getHLevelParameters(), LevelVariable.LvlType.HLVL, variables, levelFields, def.hOriginalDef);
      } else {
        typecheckLevelFields(typedDef, def, LevelVariable.HVAR, levelFields);
      }
      if (myNewDef && !(levelFields.size() == 2 && levelFields.get(0) == LevelVariable.PVAR && levelFields.get(1) == LevelVariable.HVAR)) {
        typedDef.setLevelParameters(levelFields);
      }
      typechecker.setLevelContext(new LevelContext(variables, !levelFields.isEmpty() && levelFields.get(0).getType() == LevelVariable.LvlType.PLVL, !levelFields.isEmpty() && levelFields.get(levelFields.size() - 1).getType() == LevelVariable.LvlType.HLVL));
    }

    Levels idLevels = typedDef.makeIdLevels();

    // Set super class levels
    {
      Map<ClassDefinition, Levels> superLevels = new HashMap<>();
      int i = 0;
      boolean compareLevels = (def.getPLevelParameters() == null || def.pOriginalDef != null) && (def.getHLevelParameters() == null || def.hOriginalDef != null);
      for (ClassDefinition superClass : typedDef.getSuperClasses()) {
        boolean ok = (compareLevels || superClass.getLevelParameters() != null) && compareLevelFields(typedDef, superClass, def.getSuperClasses().get(i));
        Levels defLevels = idLevels;
        if (ok && superClass.getLevelParameters() != null && defLevels.size() > superClass.getLevelParameters().size()) {
          if (!superClass.hasPLevelParameters() && !superClass.hasHLevelParameters()) {
            defLevels = Levels.EMPTY;
          } else if (superClass.hasPLevelParameters() && !superClass.hasHLevelParameters()) {
            defLevels = new ListLevels(new ArrayList<>(defLevels.toList().subList(0, superClass.getLevelParameters().size())));
          } else if (superClass.hasHLevelParameters() && !superClass.hasPLevelParameters()) {
            List<? extends Level> oldList = defLevels.toList();
            defLevels = new ListLevels(new ArrayList<>(oldList.subList(oldList.size() - superClass.getLevelParameters().size(), oldList.size())));
          }
        }
        superLevels.put(superClass, typechecker.typecheckLevels(superClass, def.getSuperClasses().get(i++), ok ? defLevels : null, false));
      }
      if (!superLevels.isEmpty()) {
        LevelEquationsSolver levelSolver = typechecker.getEquations().makeLevelEquationsSolver();
        LevelSubstitution subst = levelSolver.solveLevels();
        if (myNewDef && !subst.isEmpty()) {
          for (Map.Entry<ClassDefinition, Levels> entry : superLevels.entrySet()) {
            entry.setValue(entry.getValue().subst(subst));
          }
        }
      }

      i = 0;
      for (ClassDefinition currentClass : typedDef.getSuperClasses()) {
        Concrete.ReferenceExpression aSuperClass = def.getSuperClasses().get(i++);
        Levels currentLevels = superLevels.get(currentClass);
        if (currentLevels == null) {
          for (Map.Entry<ClassDefinition, Levels> entry : currentClass.getSuperLevels().entrySet()) {
            Levels levels = entry.getValue();
            Levels superClassLevels = superLevels.get(currentClass);
            if (superClassLevels != null) levels = levels.subst(superClassLevels.makeSubstitution(currentClass));
            Levels oldLevels = superLevels.putIfAbsent(entry.getKey(), levels);
            if (oldLevels != null && !oldLevels.equals(levels)) {
              errorReporter.report(new SuperLevelsMismatchError(entry.getKey(), oldLevels, levels, aSuperClass));
            }
          }
        } else {
          new DFS<ClassDefinition, Void>() {
            @Override
            protected Void forDependencies(ClassDefinition unit) {
              if (unit != currentClass) {
                Levels newLevels = currentClass.castLevels(unit, currentLevels);
                Levels oldLevels = superLevels.putIfAbsent(unit, newLevels);
                if (oldLevels != null && !oldLevels.equals(newLevels)) {
                  errorReporter.report(new SuperLevelsMismatchError(unit, oldLevels, newLevels, aSuperClass));
                }
              }
              for (ClassDefinition superClass : unit.getSuperClasses()) {
                visit(superClass);
              }
              return null;
            }
          }.visit(currentClass);
        }
      }

      i = 0;
      for (ClassDefinition superClass : typedDef.getSuperClasses()) {
        if (superLevels.get(superClass) != null) continue;
        for (Map.Entry<ClassDefinition, Levels> entry : superLevels.entrySet()) {
          Levels oldLevels = superLevels.get(entry.getKey());
          if (oldLevels != null && !superClass.getSuperLevels().containsKey(entry.getKey()) && superClass.isSubClassOf(entry.getKey())) {
            Levels levels = entry.getKey().makeIdLevels();
            Levels superClassLevels = superLevels.get(superClass);
            if (superClassLevels != null) levels = levels.subst(superClassLevels.makeSubstitution(superClass));
            if (!oldLevels.equals(levels)) {
              errorReporter.report(new SuperLevelsMismatchError(entry.getKey(), oldLevels, levels, def.getSuperClasses().get(i)));
            }
          }
        }
        i++;
      }

      for (Iterator<Map.Entry<ClassDefinition, Levels>> iterator = superLevels.entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<ClassDefinition, Levels> entry = iterator.next();
        Levels levels = entry.getValue();
        if (levels.compare(idLevels, CMP.EQ, DummyEquations.getInstance(), null)) {
          iterator.remove();
        }
      }

      if (!superLevels.isEmpty()) {
        typedDef.setSuperLevels(superLevels);
      }
    }

    Concrete.SourceNode alreadyImplementedSourceNode = null;
    List<FieldReferable> alreadyImplementFields = new ArrayList<>();
    for (Concrete.ReferenceExpression aSuperClass : def.getSuperClasses()) {
      Definition superClassDef = aSuperClass.getReferent() instanceof TCDefReferable ? ((TCDefReferable) aSuperClass.getReferent()).getTypechecked() : null;
      if (superClassDef instanceof ClassDefinition) {
        for (Map.Entry<ClassField, AbsExpression> entry : ((ClassDefinition) superClassDef).getImplemented()) {
          Levels levels = typedDef.getSuperLevels().get(superClassDef);
          if (!implementField(entry.getKey(), entry.getValue().subst(new ExprSubstitution(), levels == null ? idLevels.makeSubstitution(superClassDef) : levels.makeSubstitution(superClassDef)), typedDef, alreadyImplementFields)) {
            classOk = false;
            alreadyImplementedSourceNode = aSuperClass;
          }
        }
      }
    }

    boolean hasClassifyingField = false;
    if (!def.isRecord() && !def.withoutClassifying()) {
      if (def.getClassifyingField() != null) {
        hasClassifyingField = true;
      } else {
        for (ClassDefinition superClass : typedDef.getSuperClasses()) {
          if (superClass.getClassifyingField() != null) {
            hasClassifyingField = true;
            break;
          }
        }
      }
    }

    // Check for cycles in implementations from super classes
    boolean checkImplementations = true;
    FieldDFS dfs = new FieldDFS(typedDef);
    for (ClassField field : typedDef.getFields()) {
      List<ClassField> cycle = dfs.findCycle(field);
      if (cycle != null) {
        errorReporter.report(CycleError.fromTypechecked(cycle, def));
        checkImplementations = false;
        break;
      }
    }

    // Set overridden fields from super classes
    if (!typedDef.getSuperClasses().isEmpty()) {
      // Collect overridden fields
      Set<ClassField> overriddenHere = new HashSet<>();
      for (Concrete.ClassElement element : def.getElements()) {
        if (element instanceof Concrete.OverriddenField) {
          ClassField field = typechecker.referableToClassField(((Concrete.OverriddenField) element).getOverriddenField(), null);
          if (field != null) {
            overriddenHere.add(field);
          }
        }
      }

      for (ClassField field : typedDef.getFields()) {
        if (overriddenHere.contains(field)) {
          continue;
        }

        ClassDefinition originalSuperClass = null;
        PiExpression type = null;
        for (ClassDefinition superClass : typedDef.getSuperClasses()) {
          PiExpression superType = superClass.getOverriddenType(field);
          if (superType != null) {
            if (type == null) {
              originalSuperClass = superClass;
              TypedSingleDependentLink thisParam = new TypedSingleDependentLink(false, "this", new ClassCallExpression(typedDef, idLevels), true);
              type = new PiExpression(superType.getResultSort(), thisParam, superType.applyExpression(new ReferenceExpression(thisParam)));
            } else {
              if (!CompareVisitor.compare(DummyEquations.getInstance(), CMP.EQ, type.getCodomain(), superType.applyExpression(new ReferenceExpression(type.getParameters())), Type.OMEGA, def)) {
                if (!type.getCodomain().reportIfError(errorReporter, def) && !superType.getCodomain().reportIfError(errorReporter, def)) {
                  errorReporter.report(new TypecheckingError("The types of the field '" + field.getName() + "' differ in super classes '" + originalSuperClass.getName() + "' and '" + superClass.getName() + "'", def));
                }
                type = new PiExpression(type.getResultSort(), type.getParameters(), new ErrorExpression());
                break;
              }
            }
          }
        }
        if (myNewDef && type != null) {
          overrideField(field, type, typedDef, def);
        }
      }
    }

    List<LocalInstance> localInstances = new ArrayList<>();
    for (ClassField field : typedDef.getFields()) {
      addFieldInstance(field, typedDef, localInstances, false);
    }

    // Process fields and implementations
    Concrete.Expression previousType = null;
    ClassField previousField = null;
    Set<ClassField> implementedHere = new HashSet<>();
    for (Concrete.ClassElement element : def.getElements()) {
      if (element instanceof Concrete.CoClauseFunctionReference) {
        continue;
      }
      if (element instanceof Concrete.ClassField) {
        Concrete.ClassField field = (Concrete.ClassField) element;
        if (previousType == field.getResultType()) {
          if (myNewDef && previousField != null) {
            ClassField newField = addField(field.getData(), typedDef, previousField.getType(), previousField.getTypeLevel());
            newField.setStatus(previousField.status());
            newField.setUniverseKind(previousField.getUniverseKind());
            newField.setNumberOfParameters(previousField.getNumberOfParameters());
          }
        } else {
          previousType = field.getResultType();
          previousField = typecheckClassField(field, typedDef, localInstances, hasClassifyingField);
          if (previousField != null) {
            UniverseKind universeKind = new UniverseKindChecker().getUniverseKind(previousField.getType().getCodomain());
            previousField.setUniverseKind(universeKind);
            previousField.setNumberOfParameters(Concrete.getNumberOfParameters(field.getParameters()));
          }

          addFieldInstance(previousField != null ? previousField : (ClassField) field.getData().getTypechecked(), typedDef, localInstances, false);
        }
        if (field.isCoerce()) {
          ClassField classField = (ClassField) field.getData().getTypechecked();
          classField.setHideable(true);
          typedDef.getCoerceData().addCoercingField(classField, errorReporter, field);
        }
      } else if (element instanceof Concrete.ClassFieldImpl) {
        Concrete.ClassFieldImpl classFieldImpl = (Concrete.ClassFieldImpl) element;
        ClassField field = typechecker.referableToClassField(classFieldImpl.getImplementedField(), classFieldImpl);
        if (field == null) {
          classOk = false;
          continue;
        }

        if (!classFieldImpl.isDefault()) {
          boolean isFieldAlreadyImplemented;
          if (myNewDef) {
            isFieldAlreadyImplemented = typedDef.isImplemented(field);
          } else if (implementedHere.contains(field)) {
            isFieldAlreadyImplemented = true;
          } else {
            isFieldAlreadyImplemented = false;
            for (ClassDefinition superClass : typedDef.getSuperClasses()) {
              if (superClass.isImplemented(field)) {
                isFieldAlreadyImplemented = true;
                break;
              }
            }
          }
          if (isFieldAlreadyImplemented) {
            classOk = false;
            alreadyImplementFields.add(field.getReferable());
            alreadyImplementedSourceNode = classFieldImpl;
          } else {
            implementedHere.add(field);
          }

          if (isFieldAlreadyImplemented || !checkImplementations) {
            continue;
          }
        }

        typedDef.updateSort();

        TypedSingleDependentLink thisBinding = new TypedSingleDependentLink(false, "this", new ClassCallExpression(typedDef, idLevels), true);
        Concrete.LamExpression lamImpl = (Concrete.LamExpression) classFieldImpl.implementation;
        TypecheckingResult result;
        if (lamImpl != null) {
          typechecker.addBinding(lamImpl.getParameters().get(0).getReferableList().get(0), thisBinding);
          LocalInstancePool localInstancePool = new LocalInstancePool(typechecker);
          addLocalInstances(localInstances, thisBinding, idLevels, typedDef, !typedDef.isRecord() && typedDef.getClassifyingField() == null, localInstancePool);
          GlobalInstancePool instancePool = typechecker.getInstancePool().copy(typechecker);
          instancePool.setInstancePool(localInstancePool);
          typechecker.setInstancePool(instancePool);
          if (field.isProperty()) {
            CheckTypeVisitor.setCaseLevel(lamImpl.body, -1);
          }
          Levels superLevels = typedDef.getSuperLevels().get(field.getParentClass());
          Expression type = typedDef.getFieldType(field, superLevels == null ? idLevels.makeSubstitution(field) : superLevels.makeSubstitution(field), new ReferenceExpression(thisBinding));
          result = typechecker.finalCheckExpr(CheckTypeVisitor.addImplicitLamParams(lamImpl.body, type), type);
        } else {
          result = null;
        }
        if (result == null) {
          classOk = false;
        }

        typechecker.getContext().clear();

        if (result != null && !classFieldImpl.isDefault()) {
          List<ClassField> cycle = dfs.checkDependencies(field, FieldsCollector.getFields(result.expression, thisBinding, typedDef.getFields()));
          if (cycle != null) {
            errorReporter.report(CycleError.fromTypechecked(cycle, def));
            checkImplementations = false;
          }
        }

        if (result != null && classFieldImpl.isDefault()) {
          typedDef.addDefaultImplDependencies(field, FieldsCollector.getFields(result.expression, thisBinding, typedDef.getFields()));
        }

        if (myNewDef) {
          AbsExpression abs = new AbsExpression(thisBinding, checkImplementations && result != null ? result.expression : new ErrorExpression());
          if (classFieldImpl.isDefault()) {
            typedDef.addDefault(field, abs);
          } else {
            typedDef.implementField(field, abs);
          }
        }
      } else if (element instanceof Concrete.OverriddenField) {
        ClassField field = typecheckClassField((Concrete.OverriddenField) element, typedDef, localInstances, hasClassifyingField);
        if (field == null) {
          classOk = false;
        } else if (myNewDef) {
          addFieldInstance(field, typedDef, localInstances, true);
        }
      } else {
        throw new IllegalStateException();
      }
    }

    for (ClassDefinition superClass : typedDef.getSuperClasses()) {
      for (Map.Entry<ClassField, AbsExpression> entry : superClass.getDefaults()) {
        Levels levels = typedDef.getSuperLevels().get(superClass);
        typedDef.addDefaultIfAbsent(entry.getKey(), entry.getValue().subst(new ExprSubstitution(), levels == null ? idLevels.makeSubstitution(superClass) : levels.makeSubstitution(superClass)));
      }
      for (Map.Entry<ClassField, Set<ClassField>> entry : superClass.getDefaultDependencies().entrySet()) {
        typedDef.addDefaultDependencies(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<ClassField, Set<ClassField>> entry : superClass.getDefaultImplDependencies().entrySet()) {
        typedDef.addDefaultImplDependencies(entry.getKey(), entry.getValue());
      }
    }

    MapDFS<ClassField> fieldDFS = new MapDFS<>(typedDef.getDefaultDependencies());
    fieldDFS.visit(typedDef.getImplementedFields());
    for (ClassField field : fieldDFS.getVisited()) {
      typedDef.removeDefault(field);
    }

    // Set fields covariance
    Set<ClassField> covariantFields = new HashSet<>(typedDef.getFields());
    ParametersCovarianceChecker checker = new ParametersCovarianceChecker(covariantFields);
    for (ClassField field : typedDef.getFields()) {
      checker.check(field.getType().getCodomain());
      if (covariantFields.isEmpty()) {
        break;
      }
    }
    for (ClassField field : covariantFields) {
      typedDef.addCovariantField(field);
    }

    // Process classifying field
    if (!def.isRecord()) {
      ClassField classifyingField = null;
      if (!def.isForcedClassifyingField() && !typedDef.getSuperClasses().isEmpty()) {
        Set<ClassDefinition> visited = new HashSet<>();
        for (ClassDefinition superClass : typedDef.getSuperClasses()) {
          classifyingField = findClassifyingField(superClass, typedDef, visited);
          if (classifyingField != null) {
            break;
          }
        }
      }
      if (classifyingField == null && def.getClassifyingField() != null) {
        Definition definition = def.getClassifyingField().getTypechecked();
        if (definition instanceof ClassField && ((ClassField) definition).getParentClass().equals(typedDef)) {
          classifyingField = (ClassField) definition;
        } else {
          errorReporter.report(new TypecheckingError("Internal error: coercing field must be a field belonging to the class", def));
        }
      }
      if (def.withoutClassifying()) {
        if (classifyingField == null) {
          errorReporter.report(new CertainTypecheckingError(CertainTypecheckingError.Kind.NO_CLASSIFYING_IGNORED, def));
        } else {
          classifyingField = null;
        }
      }
      if (myNewDef) {
        typedDef.setClassifyingField(classifyingField);
        if (classifyingField != null) {
          if (classifyingField.getParentClass() == typedDef) {
            classifyingField.setHideable(true);
            classifyingField.setType(classifyingField.getType().normalize(NormalizationMode.WHNF));
          }
          typedDef.getCoerceData().addCoercingField(classifyingField, null, null);
        }
      }
    } else {
      if (myNewDef) {
        typedDef.setRecord();
      }
    }

    for (ClassDefinition superClass : typedDef.getSuperClasses()) {
      for (Map.Entry<CoerceData.Key, List<Definition>> entry : superClass.getCoerceData().getMapTo()) {
        if (entry.getValue().size() == 1 && entry.getValue().get(0) instanceof ClassField) {
          typedDef.getCoerceData().addCoercingField((ClassField) entry.getValue().get(0), null, null);
        }
      }
    }

    if (!alreadyImplementFields.isEmpty()) {
      errorReporter.report(new FieldsImplementationError(true, def.getData(), alreadyImplementFields, alreadyImplementFields.size() > 1 ? def : alreadyImplementedSourceNode));
    }

    if (myNewDef) {
      if (!typedDef.getOverriddenFields().isEmpty()) {
        Set<ClassField> superFields = new LinkedHashSet<>();
        for (ClassDefinition superClass : typedDef.getSuperClasses()) {
          superFields.addAll(superClass.getFields());
        }
        for (ClassField field : superFields) {
          if (field.isProperty() || typedDef.isImplemented(field) || typedDef.isOverridden(field)) {
            continue;
          }
          TypedSingleDependentLink thisParam = new TypedSingleDependentLink(false, "this", new ClassCallExpression(typedDef, idLevels), true);
          Expression type = field.getType().applyExpression(new ReferenceExpression(thisParam));
          Type newType = type.accept(new MinimizeLevelVisitor(), null);
          if (newType != null && newType != type) {
            typedDef.overrideField(field, new PiExpression(thisParam.getType().getSortOfType().max(newType.getSortOfType()), thisParam, newType.getExpr()));
          }
        }
      }

      typedDef.setStatus(!classOk ? Definition.TypeCheckingStatus.HAS_ERRORS : typechecker.getStatus());
      typedDef.updateSort();

      UniverseKind baseUniverseKind = UniverseKind.NO_UNIVERSES;
      for (ClassDefinition superClass : typedDef.getSuperClasses()) {
        baseUniverseKind = baseUniverseKind.max(superClass.getBaseUniverseKind());
        if (baseUniverseKind == UniverseKind.WITH_UNIVERSES) {
          break;
        }
      }

      if (baseUniverseKind != UniverseKind.WITH_UNIVERSES) {
        UniverseInParametersChecker checker1 = new UniverseInParametersChecker();
        for (ClassField field : typedDef.getPersonalFields()) {
          baseUniverseKind = baseUniverseKind.max(checker1.getUniverseKind(field.getResultType()));
          if (baseUniverseKind == UniverseKind.WITH_UNIVERSES) {
            break;
          }
        }
      }

      typedDef.setBaseUniverseKind(baseUniverseKind);
      UniverseKind universeKind = baseUniverseKind;
      for (ClassField field : typedDef.getFields()) {
        if (field.getUniverseKind().ordinal() > universeKind.ordinal() && !typedDef.isImplemented(field)) {
          universeKind = field.getUniverseKind();
          if (universeKind == UniverseKind.WITH_UNIVERSES) {
            break;
          }
        }
      }
      typedDef.setUniverseKind(universeKind);

      for (ClassField field : typedDef.getPersonalFields()) {
        field.getType().getParameters().setType(new ClassCallExpression(typedDef, idLevels));
      }

      Set<ClassField> goodFields = new HashSet<>(typedDef.getPersonalFields());
      GoodThisParametersVisitor visitor = new GoodThisParametersVisitor(goodFields);
      for (ClassDefinition superClass : typedDef.getSuperClasses()) {
        goodFields.addAll(superClass.getGoodThisFields());
      }
      for (ClassField field : typedDef.getPersonalFields()) {
        field.getType().getCodomain().accept(visitor, null);
      }
      for (Concrete.ClassElement element : def.getElements()) {
        if (element instanceof Concrete.ClassFieldImpl) {
          ClassField field = typechecker.referableToClassField(((Concrete.ClassFieldImpl) element).getImplementedField(), null);
          if (field != null) {
            AbsExpression impl = typedDef.getImplementation(field);
            if (impl != null) {
              impl.getExpression().accept(visitor, null);
            }
          }
        } else if (element instanceof Concrete.OverriddenField) {
          ClassField field = typechecker.referableToClassField(((Concrete.OverriddenField) element).getOverriddenField(), null);
          if (field != null) {
            field.getType().getCodomain().accept(visitor, null);
          }
        }
      }
      typedDef.setGoodThisFields(visitor.getGoodFields());

      Set<ClassField> typeClassFields = new HashSet<>();
      for (Concrete.ClassElement element : def.getElements()) {
        if (element instanceof Concrete.ClassField && ((Concrete.ClassField) element).getData().isParameterField()) {
          Concrete.Expression resultType = ((Concrete.ClassField) element).getResultType();
          if (resultType instanceof Concrete.PiExpression) {
            resultType = ((Concrete.PiExpression) resultType).getCodomain();
          }
          if (isTypeClassRef(resultType)) {
            ClassField field = typechecker.referableToClassField(((Concrete.ClassField) element).getData(), null);
            if (field != null) {
              typeClassFields.add(field);
            }
          }
        }
      }
      if (!typeClassFields.isEmpty()) {
        typedDef.setTypeClassFields(typeClassFields);
      }

      def.setTypechecked();
    }
  }

  private Pair<DependentLink, Expression> addPiParametersToContext(List<? extends Concrete.Parameter> parameters, Expression piType) {
    Expression resultType = piType;
    SingleDependentLink link = EmptyDependentLink.getInstance();
    for (Concrete.Parameter parameter : parameters) {
      for (Referable referable : parameter.getReferableList()) {
        if (!link.hasNext()) {
          if (!(resultType instanceof PiExpression)) {
            return new Pair<>(link, null);
          }
          link = ((PiExpression) resultType).getParameters();
          resultType = ((PiExpression) resultType).getCodomain();
        }
        typechecker.addBinding(referable, link);
        link = link.getNext();
      }
    }
    return new Pair<>(link, resultType);
  }

  private ClassField typecheckClassField(Concrete.BaseClassField def, ClassDefinition parentClass, List<LocalInstance> localInstances, boolean hasClassifyingField) {
    ClassField typedDef = null;
    if (def instanceof Concrete.OverriddenField) {
      typedDef = typechecker.referableToClassField(((Concrete.OverriddenField) def).getOverriddenField(), def);
      if (typedDef == null) {
        return null;
      }

      if (typedDef.getParentClass() == parentClass || !parentClass.getFields().contains(typedDef)) {
        errorReporter.report(new TypecheckingError("Overridden field must belong to a super class", def));
        return null;
      }
    }

    boolean isProperty = false;
    boolean ok;
    PiExpression piType;
    try (var ignored = new Utils.SetContextSaver<>(typechecker.getContext())) {
      Concrete.Expression codomain;
      Levels idLevels = parentClass.makeIdLevels();
      TypedSingleDependentLink thisParam = new TypedSingleDependentLink(false, "this", new ClassCallExpression(parentClass, idLevels), true);
      if (!def.getParameters().isEmpty()) {
        if (def.getParameters().size() == 1) {
          codomain = def.getResultType();
        } else {
          codomain = new Concrete.PiExpression(def.getParameters().get(1).getData(), def.getParameters().subList(1, def.getParameters().size()), def.getResultType());
        }
        typechecker.addBinding(def.getParameters().get(0).getReferableList().get(0), thisParam);
      } else {
        typechecker.addBinding(null, thisParam);
        errorReporter.report(new TypecheckingError("Internal error: class field must have a function type", def));
        codomain = def.getResultType();
      }

      LocalInstancePool localInstancePool = new LocalInstancePool(typechecker);
      addLocalInstances(localInstances, thisParam, idLevels, parentClass, !parentClass.isRecord() && !hasClassifyingField, localInstancePool);
      GlobalInstancePool instancePool = typechecker.getInstancePool().copy(typechecker);
      instancePool.setInstancePool(localInstancePool);
      typechecker.setInstancePool(instancePool);
      ClassFieldKind kind = def instanceof Concrete.ClassField ? ((Concrete.ClassField) def).getKind() : typedDef == null ? ClassFieldKind.ANY : typedDef.isProperty() ? ClassFieldKind.PROPERTY : ClassFieldKind.FIELD;
      Type typeResult = typechecker.finalCheckType(codomain, Type.OMEGA, kind == ClassFieldKind.PROPERTY && def.getResultTypeLevel() == null);
      ok = typeResult != null;
      Expression typeExpr = ok ? typeResult.getExpr() : new ErrorExpression();
      piType = new PiExpression(ok ? Sort.STD.max(typeResult.getSortOfType()) : Sort.STD, thisParam, typeExpr);

      if (myNewDef && def instanceof Concrete.ClassField) {
        typedDef = addField(((Concrete.ClassField) def).getData(), parentClass, piType, null);
      }

      if (ok && def.getResultTypeLevel() != null) {
        if (kind == ClassFieldKind.FIELD) {
          errorReporter.report(new CertainTypecheckingError(CertainTypecheckingError.Kind.LEVEL_IGNORED, def.getResultTypeLevel()));
        } else {
          var pair = addPiParametersToContext(def.getParameters(), piType);
          if (!pair.proj1.hasNext() && pair.proj2 != null) {
            Integer level = typecheckResultTypeLevel(def.getResultTypeLevel(), false, true, pair.proj2, null, typedDef, def instanceof Concrete.OverriddenField);
            isProperty = level != null && level == -1;
          } else {
            // Just reports an error
            typechecker.getExpressionLevel(pair.proj1, null, null, DummyEquations.getInstance(), def.getResultTypeLevel());
          }
        }
      } else if (ok && kind != ClassFieldKind.FIELD) {
        Sort sort = typeResult.getSortOfType();
        if (sort.isProp()) {
          isProperty = true;
        } else {
          DefCallExpression defCall = typeExpr.cast(DefCallExpression.class);
          Integer level = defCall == null ? null : defCall.getUseLevel();
          if (kind == ClassFieldKind.PROPERTY) {
            boolean check = true;
            if ((level == null || level != -1) && typechecker.getExtension() != null) {
              LevelProver prover = typechecker.getExtension().getLevelProver();
              if (prover != null) {
                var pair = addPiParametersToContext(def.getParameters(), piType);
                Type typeType;
                Expression type = pair.proj2;
                if (type == null) {
                  type = typeExpr;
                  typeType = typeResult;
                } else if (type instanceof Type) {
                  typeType = (Type) type;
                } else {
                  Sort typeSort = type.getSortOfType();
                  typeType = new TypeExpression(type, typeSort != null ? typeSort : typeResult.getSortOfType());
                }
                TypecheckingResult result = pair.proj2 == null ? null : typechecker.finalize(TypecheckingResult.fromChecked(prover.prove(null, type, CheckTypeVisitor.getLevelExpression(typeType, -1), -1, def.getResultType(), typechecker)), def.getResultType(), false);
                if (result != null) {
                  Integer level2 = checkResultTypeLevel(result, false, true, type, null, typedDef, false, def);
                  if (level2 != null && level2 == -1) {
                    isProperty = true;
                  }
                  check = false;
                }
              }
            }
            if (check && checkLevel(false, true, level, sort, def)) {
              isProperty = true;
            }
          } else {
            if (level != null && level == -1) {
              isProperty = true;
            }
          }
        }
      }
    }

    boolean newDef = myNewDef;
    if (newDef && typedDef == null) {
      throw new IllegalStateException();
    }

    GoodThisParametersVisitor goodThisParametersVisitor = new GoodThisParametersVisitor(piType.getParameters());
    piType.getCodomain().accept(goodThisParametersVisitor, null);
    List<Boolean> goodThisParams = goodThisParametersVisitor.getGoodParameters();
    if (goodThisParams.isEmpty() || !goodThisParams.get(0)) {
      errorReporter.report(new TypecheckingError("The type of the field contains illegal \\this occurrence", def.getParameters().isEmpty() ? def.getResultType() : def.getParameters().get(0)));
      ok = false;
      if (newDef && def instanceof Concrete.ClassField) {
        typedDef.setType(new PiExpression(piType.getResultSort(), piType.getParameters(), new ErrorExpression()));
      }
    }

    if (def instanceof Concrete.OverriddenField) {
      if (!CompareVisitor.compare(DummyEquations.getInstance(), CMP.LE, piType.getCodomain(), typedDef.getType().applyExpression(new ReferenceExpression(piType.getParameters())), Type.OMEGA, def)) {
        if (!piType.getCodomain().reportIfError(errorReporter, def) && !typedDef.getType().getCodomain().reportIfError(errorReporter, def)) {
          errorReporter.report(new TypecheckingError("The type of the overridden field is not compatible with the specified type", def));
        }
        ok = false;
      }
      for (ClassDefinition superClass : parentClass.getSuperClasses()) {
        if (!ok) {
          break;
        }
        PiExpression superType = superClass.getOverriddenType(typedDef);
        if (superType != null && !CompareVisitor.compare(DummyEquations.getInstance(), CMP.LE, piType.getCodomain(), superType.applyExpression(new ReferenceExpression(piType.getParameters())), Type.OMEGA, def)) {
          if (!piType.getCodomain().reportIfError(errorReporter, def) && !superType.getCodomain().reportIfError(errorReporter, def)) {
            errorReporter.report(new TypecheckingError("The type of the field in super class '" + superClass.getName() + "' is not compatible with the specified type", def));
          }
          ok = false;
        }
      }
      if (newDef) {
        overrideField(typedDef, ok ? piType : new PiExpression(piType.getResultSort(), piType.getParameters(), new ErrorExpression()), parentClass, def);
      }
      if (!ok) {
        return null;
      }
    }

    if (!newDef) {
      return null;
    }

    if (isProperty && def instanceof Concrete.ClassField) {
      typedDef.setIsProperty();
    }
    if (!ok) {
      typedDef.setStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
    }
    return typedDef;
  }

  private void addLocalInstances(List<LocalInstance> localInstances, Binding thisParam, Levels levels, ClassDefinition classDef, boolean useClassDef, LocalInstancePool localInstancePool) {
    if (localInstances.isEmpty() && !useClassDef) {
      return;
    }

    if (useClassDef) {
      localInstancePool.addLocalInstance(null, classDef, new ReferenceExpression(thisParam));
    }
    for (LocalInstance localInstance : localInstances) {
      ClassField classifyingField = localInstance.classCall.getDefinition().getClassifyingField();
      Expression instance = FieldCallExpression.make(localInstance.instanceField, classDef.castLevels(localInstance.instanceField.getParentClass(), levels), new ReferenceExpression(thisParam));
      if (classifyingField == null) {
        localInstancePool.addLocalInstance(null, localInstance.classCall.getDefinition(), instance);
      } else {
        localInstancePool.addLocalInstance(FieldCallExpression.make(classifyingField, localInstance.classCall.getLevels(classifyingField.getParentClass()), instance), localInstance.classCall.getDefinition(), instance);
      }
    }
  }

  private ClassField addField(TCFieldReferable fieldRef, ClassDefinition parentClass, PiExpression piType, Expression typeLevel) {
    ClassField typedDef = new ClassField(fieldRef, parentClass, piType, typeLevel);
    fieldRef.setTypechecked(typedDef);
    parentClass.addField(typedDef);
    parentClass.addPersonalField(typedDef);
    return typedDef;
  }

  private static boolean implementField(ClassField classField, AbsExpression implementation, ClassDefinition classDef, List<FieldReferable> alreadyImplemented) {
    AbsExpression oldImpl = classDef.implementField(classField, implementation);
    ReferenceExpression thisRef = new ReferenceExpression(classField.getType().getParameters());
    if (oldImpl != null && !classField.isProperty() && !Expression.compare(oldImpl.apply(thisRef, LevelSubstitution.EMPTY), implementation.apply(thisRef, LevelSubstitution.EMPTY), classField.getType().getCodomain(), CMP.EQ)) {
      alreadyImplemented.add(classField.getReferable());
      return false;
    } else {
      return true;
    }
  }

  private void overrideField(ClassField field, PiExpression type, ClassDefinition classDef, Concrete.SourceNode sourceNode) {
    AbsExpression impl = classDef.getImplementation(field);
    if (impl != null) {
      Expression implType = impl.apply(new ReferenceExpression(type.getParameters()), LevelSubstitution.EMPTY).computeType();
      if (!implType.isLessOrEquals(type.getCodomain(), DummyEquations.getInstance(), sourceNode)) {
        errorReporter.report(new TypeMismatchError("Cannot override field '" + field.getName() + "'", type.getCodomain(), implType, sourceNode));
        return;
      }
    }
    classDef.overrideField(field, type);
  }

  public static void setDefaultDependencies(Concrete.ClassDefinition concreteDef) {
    Definition classDef = concreteDef.getData().getTypechecked();
    if (!(classDef instanceof ClassDefinition)) return;
    Map<FunctionDefinition, ClassField> funcMap = new HashMap<>();
    for (Concrete.ClassElement element : concreteDef.getElements()) {
      if (element instanceof Concrete.CoClauseFunctionReference) {
        Definition def = ((Concrete.CoClauseFunctionReference) element).getFunctionReference().getTypechecked();
        Referable ref = ((Concrete.CoClauseFunctionReference) element).getImplementedRef();
        Definition field = ref instanceof TCDefReferable ? ((TCDefReferable) ref).getTypechecked() : null;
        if (def instanceof FunctionDefinition && field instanceof ClassField) {
          funcMap.put((FunctionDefinition) def, (ClassField) field);
        }
      }
    }

    for (Concrete.ClassElement element : concreteDef.getElements()) {
      if (element instanceof Concrete.CoClauseFunctionReference) {
        Definition def = ((Concrete.CoClauseFunctionReference) element).getFunctionReference().getTypechecked();
        Referable ref = ((Concrete.CoClauseFunctionReference) element).getImplementedRef();
        Definition field = ref instanceof TCDefReferable ? ((TCDefReferable) ref).getTypechecked() : null;
        if (def instanceof FunctionDefinition && field instanceof ClassField) {
          FindDefCallVisitor<FunctionDefinition> visitor = new FindDefCallVisitor<>(funcMap.keySet(), true);
          visitor.visitFunction((FunctionDefinition) def, null);
          if (!visitor.getFoundDefinitions().isEmpty()) {
            for (FunctionDefinition func : visitor.getFoundDefinitions()) {
              ClassField field2 = funcMap.get(func);
              if (field2 != null) {
                ((ClassDefinition) classDef).addDefaultDependency(field2, (ClassField) field);
              }
            }
          }

          ((ClassDefinition) classDef).addDefaultImplDependencies((ClassField) field, FieldsCollector.getFields(((FunctionDefinition) def).getBody(), def.getParameters(), ((ClassDefinition) classDef).getFields()));
        }
      }
    }
  }

  private int compareExpressions(Expression expr1, Expression expr2, Expression type) {
    if (expr2 instanceof ErrorExpression) {
      return 1;
    }
    expr1 = expr1.normalize(NormalizationMode.WHNF);

    while (expr2 instanceof LamExpression) {
      expr2 = ((LamExpression) expr2).getBody();
    }

    if (expr2 instanceof UniverseExpression) {
      return expr1 instanceof UniverseExpression && ((UniverseExpression) expr1).getSort().equals(((UniverseExpression) expr2).getSort()) ? 0 : 1;
    }

    if (expr2 instanceof IntegerExpression) {
      return expr1 instanceof IntegerExpression ? ((IntegerExpression) expr1).compare((IntegerExpression) expr2) : 1;
    }

    if (expr2 instanceof DataCallExpression || expr2 instanceof FunCallExpression && ((FunCallExpression) expr2).getDefinition().getKind() == CoreFunctionDefinition.Kind.TYPE) {
      int cmp = 0;
      if (expr1 instanceof DefCallExpression && ((DefCallExpression) expr1).getDefinition() == ((DefCallExpression) expr2).getDefinition()) {
        ExprSubstitution substitution = new ExprSubstitution();
        DependentLink link = ((DefCallExpression) expr1).getDefinition().getParameters();
        List<? extends Expression> args1 = ((DefCallExpression) expr1).getDefCallArguments();
        List<? extends Expression> args2 = ((DefCallExpression) expr2).getDefCallArguments();
        for (int i = 0; i < args1.size(); i++) {
          int argCmp = compareExpressions(args1.get(i), args2.get(i), link.getTypeExpr().subst(substitution));
          if (argCmp == 1) {
            cmp = 1;
            break;
          }
          if (argCmp == -1) {
            cmp = -1;
          }

          substitution.add(link, args1.get(i));
          link = link.getNext();
        }
        if (cmp == -1) {
          return -1;
        }
      }

      for (Expression arg : ((DefCallExpression) expr2).getDefCallArguments()) {
        if (compareExpressions(expr1, arg, null) != 1) {
          return -1;
        }
      }

      return cmp;
    }

    if (expr2 instanceof SigmaExpression || expr2 instanceof PiExpression) {
      int cmp = 0;
      if (expr1 instanceof SigmaExpression && expr2 instanceof SigmaExpression || expr1 instanceof PiExpression && expr2 instanceof PiExpression) {
        DependentLink param1 = getExprParameters(expr1);
        DependentLink param2 = getExprParameters(expr2);
        if (DependentLink.Helper.size(param1) == DependentLink.Helper.size(param2)) {
          for (; param1.hasNext(); param1 = param1.getNext(), param2 = param2.getNext()) {
            int argCmp = compareExpressions(param1.getTypeExpr(), param2.getTypeExpr(), Type.OMEGA);
            if (argCmp == 1) {
              cmp = 1;
              break;
            }
            if (argCmp == -1) {
              cmp = -1;
            }
          }
          if (cmp != 1 && expr1 instanceof PiExpression) {
            int codCmp = compareExpressions(((PiExpression) expr1).getCodomain(), ((PiExpression) expr2).getCodomain(), Type.OMEGA);
            if (codCmp != 0) cmp = codCmp;
          }
          if (cmp == -1) {
            return -1;
          }
        }
      }

      for (DependentLink param = getExprParameters(expr2); param.hasNext(); param = param.getNext()) {
        param = param.getNextTyped(null);
        if (compareExpressions(expr1, param.getTypeExpr(), Type.OMEGA) != 1) {
          return -1;
        }
      }
      if (expr2 instanceof PiExpression && compareExpressions(expr1, ((PiExpression) expr2).getCodomain(), Type.OMEGA) != 1) {
        return -1;
      }

      return cmp;
    }

    if (expr2 instanceof ClassCallExpression) {
      int cmp = 0;
      if (expr1 instanceof ClassCallExpression && ((ClassCallExpression) expr1).getDefinition() == ((ClassCallExpression) expr2).getDefinition() && ((ClassCallExpression) expr1).getImplementedHere().size() == ((ClassCallExpression) expr2).getImplementedHere().size()) {
        for (Map.Entry<ClassField, Expression> entry : ((ClassCallExpression) expr1).getImplementedHere().entrySet()) {
          Expression impl2 = ((ClassCallExpression) expr2).getAbsImplementationHere(entry.getKey());
          if (impl2 == null) {
            cmp = 1;
            break;
          }

          int argCmp = compareExpressions(entry.getValue(), impl2, null);
          if (argCmp == 1) {
            cmp = 1;
            break;
          }
          if (argCmp == -1) {
            cmp = -1;
          }
        }
        if (cmp == -1) {
          return -1;
        }
      }

      for (Expression arg : ((ClassCallExpression) expr2).getImplementedHere().values()) {
        if (compareExpressions(expr1, arg, null) != 1) {
          return -1;
        }
      }

      return cmp;
    }

    while (expr1 instanceof FieldCallExpression && !(expr2 instanceof FieldCallExpression && ((FieldCallExpression) expr2).getDefinition() == ((FieldCallExpression) expr1).getDefinition())) {
      expr1 = ((FieldCallExpression) expr1).getArgument();
    }
    return Expression.compare(expr1, expr2, type, CMP.EQ) ? 0 : 1;
  }

  private static DependentLink getExprParameters(Expression expr) {
    return expr instanceof SigmaExpression ? ((SigmaExpression) expr).getParameters() : expr instanceof PiExpression ? ((PiExpression) expr).getParameters() : null;
  }
}
