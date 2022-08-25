package org.arend.core.definition;

import org.arend.core.context.binding.LevelVariable;
import org.arend.ext.util.Pair;
import org.arend.naming.reference.TCDefReferable;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class TopLevelDefinition extends Definition {
  private UniverseKind myUniverseKind = UniverseKind.NO_UNIVERSES;
  private List<? extends LevelVariable> myLevelParameters;
  private Definition myPLevelsParent;
  private Definition myHLevelsParent;
  private boolean myPLevelsDerived;
  private boolean myHLevelsDerived;
  private List<Pair<TCDefReferable,Integer>> myParametersOriginalDefinitions = Collections.emptyList();
  private Set<? extends FunctionDefinition> myAxioms = Collections.emptySet();

  public TopLevelDefinition(TCDefReferable referable, TypeCheckingStatus status) {
    super(referable, status);
  }

  @Override
  public TopLevelDefinition getTopLevelDefinition() {
    return this;
  }

  @Override
  public UniverseKind getUniverseKind() {
    return myUniverseKind;
  }

  public void setUniverseKind(UniverseKind kind) {
    myUniverseKind = kind;
  }

  @Override
  public List<? extends LevelVariable> getLevelParameters() {
    return myLevelParameters;
  }

  public void setLevelParameters(List<LevelVariable> parameters) {
    myLevelParameters = parameters;
  }

  @Override
  public Definition getPLevelsParent() {
    return myPLevelsParent;
  }

  @Override
  public Definition getHLevelsParent() {
    return myHLevelsParent;
  }

  public void setPLevelsParent(Definition parent) {
    myPLevelsParent = parent;
  }

  public void setHLevelsParent(Definition parent) {
    myHLevelsParent = parent;
  }

  @Override
  public boolean arePLevelsDerived() {
    return myPLevelsDerived;
  }

  @Override
  public boolean areHLevelsDerived() {
    return myHLevelsDerived;
  }

  public void setPLevelsDerived(boolean derived) {
    myPLevelsDerived = derived;
  }

  public void setHLevelsDerived(boolean derived) {
    myHLevelsDerived = derived;
  }

  @Override
  public List<? extends Pair<TCDefReferable,Integer>> getParametersOriginalDefinitions() {
    return myParametersOriginalDefinitions;
  }

  public void setParametersOriginalDefinitions(List<Pair<TCDefReferable,Integer>> definitions) {
    myParametersOriginalDefinitions = definitions;
  }

  @Override
  public Set<? extends FunctionDefinition> getAxioms() {
    return myAxioms;
  }

  public boolean isAxiom() {
    return false;
  }

  public void setAxioms(Set<? extends FunctionDefinition> axioms) {
    myAxioms = axioms;
  }
}