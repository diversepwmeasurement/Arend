package com.jetbrains.jetpad.vclang.term.provider;

import com.jetbrains.jetpad.vclang.module.source.SourceId;
import com.jetbrains.jetpad.vclang.naming.FullName;
import com.jetbrains.jetpad.vclang.naming.reference.GlobalReferable;
import com.jetbrains.jetpad.vclang.term.DefinitionLocator;

public interface SourceInfoProvider<SourceIdT extends SourceId> extends DefinitionLocator<SourceIdT>, FullNameProvider {
  class Trivial implements SourceInfoProvider {
    @Override
    public FullName fullNameFor(GlobalReferable definition) {
      return new FullName(definition.textRepresentation());
    }

    @Override
    public SourceId sourceOf(GlobalReferable definition) {
      return null;
    }
  }

  SourceInfoProvider TRIVIAL = new Trivial();
}
