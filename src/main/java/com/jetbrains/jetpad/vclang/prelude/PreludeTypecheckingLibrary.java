package com.jetbrains.jetpad.vclang.prelude;

import com.jetbrains.jetpad.vclang.library.LibraryManager;
import com.jetbrains.jetpad.vclang.module.ModulePath;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckerState;
import com.jetbrains.jetpad.vclang.typechecking.Typechecking;

import java.util.Collection;
import java.util.Collections;

/**
 * A library which is used to load and typecheck prelude.
 */
public abstract class PreludeTypecheckingLibrary extends PreludeLibrary {
  private boolean myTypechecked = false;

  /**
   * Creates a new {@code PreludeTypecheckingLibrary}
   *
   * @param typecheckerState the underling typechecker state of this library.
   */
  public PreludeTypecheckingLibrary(TypecheckerState typecheckerState) {
    super(typecheckerState);
  }

  @Override
  public boolean load(LibraryManager libraryManager) {
    synchronized (PreludeLibrary.class) {
      if (getPreludeScope() == null) {
        return super.load(libraryManager);
      }
    }

    myTypechecked = true;
    Prelude.fillInTypecheckerState(getTypecheckerState());
    setLoaded();
    return true;
  }

  @Override
  public Collection<? extends ModulePath> getUpdatedModules() {
    return myTypechecked ? Collections.emptyList() : Collections.singleton(Prelude.MODULE_PATH);
  }

  @Override
  public boolean needsTypechecking() {
    return !myTypechecked;
  }

  @Override
  public boolean typecheck(Typechecking typechecking) {
    if (myTypechecked) {
      return true;
    }

    if (super.typecheck(typechecking)) {
      synchronized (PreludeLibrary.class) {
        if (Prelude.INTERVAL == null) {
          Prelude.initialize(getPreludeScope(), getTypecheckerState());
        }
      }
      return true;
    } else {
      return false;
    }
  }
}