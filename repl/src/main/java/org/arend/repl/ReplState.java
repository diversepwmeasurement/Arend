package org.arend.repl;

import org.arend.core.expr.Expression;
import org.arend.core.expr.visitor.ToAbstractVisitor;
import org.arend.error.ListErrorReporter;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.GeneralError;
import org.arend.ext.module.ModulePath;
import org.arend.ext.prettyprinting.PrettyPrinterConfig;
import org.arend.ext.reference.Precedence;
import org.arend.extImpl.DefinitionRequester;
import org.arend.library.Library;
import org.arend.library.LibraryManager;
import org.arend.library.SourceLibrary;
import org.arend.library.resolver.LibraryResolver;
import org.arend.module.scopeprovider.ModuleScopeProvider;
import org.arend.naming.reference.Referable;
import org.arend.naming.reference.TCReferable;
import org.arend.naming.reference.converter.IdReferableConverter;
import org.arend.naming.resolving.visitor.DefinitionResolveNameVisitor;
import org.arend.naming.resolving.visitor.ExpressionResolveNameVisitor;
import org.arend.naming.scope.CachingScope;
import org.arend.naming.scope.MergeScope;
import org.arend.naming.scope.Scope;
import org.arend.naming.scope.ScopeFactory;
import org.arend.prelude.PreludeLibrary;
import org.arend.prelude.PreludeResourceLibrary;
import org.arend.repl.action.*;
import org.arend.term.concrete.Concrete;
import org.arend.term.group.Group;
import org.arend.term.prettyprint.PrettyPrintVisitor;
import org.arend.typechecking.LibraryArendExtensionProvider;
import org.arend.typechecking.TypecheckerState;
import org.arend.typechecking.instance.pool.GlobalInstancePool;
import org.arend.typechecking.instance.provider.EmptyInstanceProvider;
import org.arend.typechecking.instance.provider.InstanceProviderSet;
import org.arend.typechecking.order.PartialComparator;
import org.arend.typechecking.order.listener.TypecheckingOrderingListener;
import org.arend.typechecking.provider.ConcreteProvider;
import org.arend.typechecking.result.TypecheckingResult;
import org.arend.typechecking.visitor.CheckTypeVisitor;
import org.arend.typechecking.visitor.SyntacticDesugarVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public abstract class ReplState implements ReplApi {
  private final List<Scope> myMergedScopes = new ArrayList<>();
  private final List<ReplHandler> myHandlers = new ArrayList<>();
  private final Set<ModulePath> myModules;
  private final MergeScope myScope = new MergeScope(myMergedScopes);
  private final SourceLibrary myReplLibrary;
  private final TypecheckerState myTypecheckerState;
  private final ConcreteProvider myConcreteProvider;
  private final TypecheckingOrderingListener myTypechecking;
  protected final @NotNull PrettyPrinterConfig myPpConfig = PrettyPrinterConfig.DEFAULT;
  protected final @NotNull ListErrorReporter myErrorReporter;
  protected final @NotNull LibraryManager myLibraryManager;

  private final @NotNull PrintStream myStdout;
  private final @NotNull PrintStream myStderr;

  public ReplState(@NotNull ListErrorReporter listErrorReporter,
                   @NotNull LibraryResolver libraryResolver,
                   @NotNull ConcreteProvider concreteProvider,
                   @NotNull PartialComparator<TCReferable> comparator,
                   @NotNull PrintStream stdout,
                   @NotNull PrintStream stderr,
                   @NotNull Set<ModulePath> modules,
                   @NotNull SourceLibrary replLibrary,
                   @NotNull TypecheckerState typecheckerState) {
    myErrorReporter = listErrorReporter;
    myConcreteProvider = concreteProvider;
    myModules = modules;
    myTypecheckerState = typecheckerState;
    myStdout = stdout;
    myStderr = stderr;
    myReplLibrary = replLibrary;
    var instanceProviders = new InstanceProviderSet();
    myLibraryManager = new LibraryManager(libraryResolver, instanceProviders, myErrorReporter, myErrorReporter, DefinitionRequester.INSTANCE);
    myTypechecking = new TypecheckingOrderingListener(instanceProviders, myTypecheckerState, myConcreteProvider, IdReferableConverter.INSTANCE, myErrorReporter, comparator, new LibraryArendExtensionProvider(myLibraryManager));
  }

  private void loadPreludeLibrary() {
    if (!loadLibrary(new PreludeResourceLibrary(myTypecheckerState)))
      eprintln("[FATAL] Failed to load Prelude");
    else myMergedScopes.add(PreludeLibrary.getPreludeScope());
  }

  private void loadReplLibrary() {
    if (!myLibraryManager.loadLibrary(myReplLibrary, myTypechecking))
      eprintln("[FATAL] Failed to load the REPL virtual library");
  }

  @Override
  public final boolean loadLibrary(@NotNull Library library) {
    if (!myLibraryManager.loadLibrary(library, myTypechecking)) return false;
    myLibraryManager.registerDependency(myReplLibrary, library);
    return true;
  }

  public final void initialize() {
    loadPreludeLibrary();
    loadReplLibrary();
    loadCommands();
  }

  public final void repl(@NotNull Supplier<@NotNull String> lineSupplier, @NotNull String currentLine) {
    for (var action : myHandlers)
      if (action.isApplicable(currentLine)) action.invoke(currentLine, this, lineSupplier);
  }

  @Override
  public @Nullable Scope loadModule(@NotNull ModulePath modulePath) {
    boolean isLoadedBefore = myModules.add(modulePath);
    myLibraryManager.reload(myTypechecking);
    if (checkErrors()) {
      myModules.remove(modulePath);
      return null;
    }
    if (isLoadedBefore) {
      Scope scope = getAvailableModuleScopeProvider().forModule(modulePath);
      if (scope != null) removeScope(scope);
    }
    myTypechecking.typecheckLibrary(myReplLibrary);
    return getAvailableModuleScopeProvider().forModule(modulePath);
  }

  @Override
  public boolean unloadModule(@NotNull ModulePath modulePath) {
    boolean isLoadedBefore = myModules.remove(modulePath);
    if (isLoadedBefore) {
      myLibraryManager.reload(myTypechecking);
      Scope scope = getAvailableModuleScopeProvider().forModule(modulePath);
      if (scope != null) removeScope(scope);
      myReplLibrary.onGroupLoaded(modulePath, null, true);
      myTypechecking.typecheckLibrary(myReplLibrary);
    }
    return isLoadedBefore;
  }

  @Override
  public @NotNull ModuleScopeProvider getAvailableModuleScopeProvider() {
    return module -> {
      for (Library registeredLibrary : myLibraryManager.getRegisteredLibraries()) {
        Scope scope = myLibraryManager.getAvailableModuleScopeProvider(registeredLibrary).forModule(module);
        if (scope != null) return scope;
      }
      return null;
    };
  }

  public @NotNull String prompt() {
    return "\u03bb ";
  }

  protected abstract @Nullable Group parseStatements(String line);

  protected abstract @Nullable Concrete.Expression parseExpr(@NotNull String text);

  @Override
  public void checkStatements(@NotNull String line) {
    var group = parseStatements(line);
    if (group == null) return;
    var moduleScopeProvider = getAvailableModuleScopeProvider();
    Scope scope = CachingScope.make(ScopeFactory.forGroup(group, moduleScopeProvider));
    myMergedScopes.add(scope);
    new DefinitionResolveNameVisitor(myConcreteProvider, myErrorReporter)
        .resolveGroupWithTypes(group, null, myScope);
    if (checkErrors()) {
      myMergedScopes.remove(scope);
      return;
    }
    myLibraryManager.getInstanceProviderSet().collectInstances(group,
        CachingScope.make(ScopeFactory.parentScopeForGroup(group, moduleScopeProvider, true)),
        myConcreteProvider, null);
    if (checkErrors()) {
      myMergedScopes.remove(scope);
      return;
    }
    if (!myTypechecking.typecheckModules(Collections.singletonList(group), null)) {
      checkErrors();
      myMergedScopes.remove(scope);
    }
  }

  protected void loadCommands() {
    myHandlers.add(CodeParsingHandler.INSTANCE);
    myHandlers.add(CommandHandler.INSTANCE);
    registerAction("unload", UnloadModuleCommand.INSTANCE);
    registerAction("modules", ListLoadedModulesAction.INSTANCE);
    registerAction("type", ShowTypeCommand.INSTANCE);
    registerAction("t", ShowTypeCommand.INSTANCE);
    registerAction("load", LoadModuleCommand.INSTANCE);
    registerAction("l", LoadModuleCommand.INSTANCE);
    registerAction("reload", LoadModuleCommand.ReloadModuleCommand.INSTANCE);
    registerAction("r", LoadModuleCommand.ReloadModuleCommand.INSTANCE);
    for (NormalizationMode normalizationMode : NormalizationMode.values()) {
      var name = normalizationMode.name().toLowerCase();
      registerAction(name, new NormalizeCommand(normalizationMode));
    }
  }

  @Override
  public final @Nullable ReplCommand registerAction(@NotNull String name, @NotNull ReplCommand action) {
    return CommandHandler.INSTANCE.commandMap.put(name, action);
  }

  @Override
  public final @Nullable ReplCommand unregisterAction(@NotNull String name) {
    return CommandHandler.INSTANCE.commandMap.remove(name);
  }

  @Override
  public final void clearActions() {
    CommandHandler.INSTANCE.commandMap.clear();
  }

  @Override
  public @NotNull Library getReplLibrary() {
    return myReplLibrary;
  }

  @Override
  public final void addScope(@NotNull Scope scope) {
    myMergedScopes.add(scope);
  }

  @Override
  public final boolean removeScope(@NotNull Scope scope) {
    for (Referable element : scope.getElements())
      if (element instanceof TCReferable)
        myTypecheckerState.reset((TCReferable) element);
    return myMergedScopes.remove(scope);
  }

  @Override
  public void println(Object anything) {
    myStdout.println(anything);
  }

  @Override
  public void print(Object anything) {
    myStdout.print(anything);
    myStdout.flush();
  }

  @Override
  public void eprintln(Object anything) {
    myStderr.println(anything);
    myStderr.flush();
  }

  @Override
  public @NotNull StringBuilder prettyExpr(@NotNull StringBuilder builder, @NotNull Expression expression) {
    var abs = ToAbstractVisitor.convert(expression, myPpConfig);
    abs.accept(new PrettyPrintVisitor(builder, 0), new Precedence(Concrete.Expression.PREC));
    return builder;
  }

  @Override
  public @Nullable TypecheckingResult checkExpr(@NotNull Concrete.Expression expr, @Nullable Expression expectedType) {
    var typechecker = new CheckTypeVisitor(myTypecheckerState, myErrorReporter, null, null);
    var instancePool = new GlobalInstancePool(EmptyInstanceProvider.getInstance(), typechecker);
    typechecker.setInstancePool(instancePool);
    var result = typechecker.checkExpr(expr, expectedType);
    return checkErrors() ? null : result;
  }

  @Override
  public @Nullable Concrete.Expression preprocessExpr(@NotNull String text) {
    var expr = parseExpr(text);
    if (expr == null || checkErrors()) return null;
    expr = expr
        .accept(new ExpressionResolveNameVisitor(myConcreteProvider,
            myScope, Collections.emptyList(), myErrorReporter, null), null)
        .accept(new SyntacticDesugarVisitor(myErrorReporter), null);
    if (checkErrors()) return null;
    return expr;
  }

  @Override
  public final boolean checkErrors() {
    var errorList = myErrorReporter.getErrorList();
    for (GeneralError error : errorList)
      (error.isSevere() ? myStderr : myStdout).println(error.getDoc(myPpConfig));
    boolean hasErrors = !errorList.isEmpty();
    errorList.clear();
    return hasErrors;
  }
}
