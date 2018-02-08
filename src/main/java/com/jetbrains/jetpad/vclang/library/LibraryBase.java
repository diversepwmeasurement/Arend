package com.jetbrains.jetpad.vclang.library;

import com.jetbrains.jetpad.vclang.module.ModulePath;
import com.jetbrains.jetpad.vclang.module.scopeprovider.ModuleScopeProvider;
import com.jetbrains.jetpad.vclang.naming.scope.LexicalScope;
import com.jetbrains.jetpad.vclang.term.Group;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckerState;

import javax.annotation.Nonnull;

/**
 * Provides a basic implementation of some of the methods of {@link Library}.
 */
public abstract class LibraryBase implements Library {
  private final TypecheckerState myTypecheckerState;
  private boolean myLoaded = false;

  /**
   * Creates a new {@code LibraryBase}
   *
   * @param typecheckerState  the underling typechecker state of this library.
   */
  protected LibraryBase(TypecheckerState typecheckerState) {
    myTypecheckerState = typecheckerState;
  }

  @Nonnull
  @Override
  public TypecheckerState getTypecheckerState() {
    return myTypecheckerState;
  }

  @Override
  public boolean load(LibraryManager libraryManager) {
    myLoaded = true;
    return true;
  }

  @Override
  public void unload() {
    TypecheckerState typecheckerState = getTypecheckerState();
    for (ModulePath modulePath : getLoadedModules()) {
      Group group = getModuleGroup(modulePath);
      if (group != null) {
        unloadGroup(typecheckerState, group);
      }
    }
    myLoaded = false;
  }

  private void unloadGroup(TypecheckerState typecheckerState, Group group) {
    typecheckerState.reset(group.getReferable());
    for (Group subgroup : group.getSubgroups()) {
      unloadGroup(typecheckerState, subgroup);
    }
    for (Group subgroup : group.getDynamicSubgroups()) {
      unloadGroup(typecheckerState, subgroup);
    }
  }

  @Override
  public boolean isLoaded() {
    return myLoaded;
  }

  @Nonnull
  @Override
  public ModuleScopeProvider getModuleScopeProvider() {
    return module -> {
      Group group = getModuleGroup(module);
      return group == null ? null : LexicalScope.opened(group);
    };
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    SourceLibrary that = (SourceLibrary) o;

    return getName().equals(that.getName());
  }

  @Override
  public int hashCode() {
    return getName().hashCode();
  }
}
