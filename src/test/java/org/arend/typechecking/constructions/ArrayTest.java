package org.arend.typechecking.constructions;

import org.arend.Matchers;
import org.arend.core.definition.ClassField;
import org.arend.core.definition.Definition;
import org.arend.core.definition.FunctionDefinition;
import org.arend.core.definition.UniverseKind;
import org.arend.core.expr.*;
import org.arend.core.sort.Sort;
import org.arend.prelude.Prelude;
import org.arend.typechecking.TypeCheckingTestCase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.arend.core.expr.ExpressionFactory.Nat;
import static org.junit.Assert.*;

public class ArrayTest extends TypeCheckingTestCase {
  @Test
  public void classExt() {
    typeCheckModule(
      "\\func test1 => \\new Array Nat 1 (\\lam _ => 3)\n" +
      "\\func test2 : Array Nat \\cowith\n" +
      "  | len => 3\n" +
      "  | at _ => 1");
    Sort sort = Sort.STD.max(Sort.SET0);
    assertTrue(((ClassCallExpression) ((FunctionDefinition) getDefinition("test1")).getResultType()).isImplemented(Prelude.ARRAY_AT));
    assertEquals(Sort.PROP, ((ClassCallExpression) ((FunctionDefinition) getDefinition("test1")).getResultType()).getSort());
    assertTrue(((ClassCallExpression) ((FunctionDefinition) getDefinition("test2")).getResultType()).isImplemented(Prelude.ARRAY_AT));
    assertEquals(Sort.PROP, ((ClassCallExpression) ((FunctionDefinition) getDefinition("test2")).getResultType()).getSort());
    assertFalse(((ClassCallExpression) Prelude.EMPTY_ARRAY.getResultType()).isImplemented(Prelude.ARRAY_AT));
    assertEquals(sort, ((ClassCallExpression) Prelude.EMPTY_ARRAY.getResultType()).getSort());
    assertFalse(((ClassCallExpression) Prelude.ARRAY_CONS.getResultType()).isImplemented(Prelude.ARRAY_AT));
    assertEquals(sort, ((ClassCallExpression) Prelude.ARRAY_CONS.getResultType()).getSort());
  }

  @Test
  public void consTest() {
    FunctionDefinition def = (FunctionDefinition) typeCheckDef("\\func test (n : Nat) (x : Array Nat n) : Array Nat (suc n) => n Array.cons x");
    assertTrue(def.getBody() instanceof ArrayExpression);
    assertEquals(Sort.SET0, ((ClassCallExpression) def.getResultType()).getSort());
  }

  @Test
  public void indexTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func array : Array Nat 2 => 14 cons 22 cons empty\n" +
      "\\lemma test1 : array.at 0 = 14 => idp\n" +
      "\\lemma test2 : array.at 1 = 22 => idp\n" +
      "\\lemma test3 : array !! 0 = 14 => idp\n" +
      "\\lemma test4 : array !! 1 = 22 => idp\n" +
      "\\func test5 (a : Array) (i : Fin a.len) : a.at i = a !! i => idp");
  }

  @Test
  public void extendsTest() {
    resolveNamesDef("\\record R \\extends Array", 1);
    assertThatErrorsAre(Matchers.notInScope("Array"));
  }

  @Test
  public void etaTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test1 (a b : Array Nat 0) : a = b => idp\n" +
      "\\func test2 (a : Array { | len => 0 }) : a = empty => idp\n" +
      "\\func test3 (a : Array { | len => 0 }) : empty = a => idp");
  }

  @Test
  public void etaError() {
    typeCheckDef("\\func test (a b : Array { | len => 0 }) : a = b => idp", 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }

  @Test
  public void newConsTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test1 : (\\new Array Nat 2 (\\case __ \\with { | 0 => 5 | 1 => 7 })) = 5 cons 7 cons empty => idp\n" +
      "\\lemma test2 : (\\new Array Nat 3 (\\case __ \\with { | 0 => 5 | suc i => \\case i \\with { | 0 => 7 | 1 => 12 } })) = (\\new Array Nat 3 (\\case __ \\with { | 0 => 5 | 1 => 7 | 2 => 12 })) => idp");
  }

  @Test
  public void cowithIndexTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f (a : Array Nat) : Array Nat a.len (\\lam _ => 7) \\cowith\n" +
      "\\lemma test (a : Array Nat) (i : Fin a.len) : f a !! i = 7 => idp");
  }

  @Test
  public void consEtaTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func map {A B : \\Type} (f : A -> B) (as : Array A) : Array B as.len (\\lam i => f (as.at i)) \\cowith\n" +
      "\\func test {A B : \\Type} (f : A -> B) {a : A} (as : Array A) : map f (a cons as) = f a cons map f as => idp");
  }

  @Test
  public void disjointConstructorsTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test1 (p : 1 cons 2 cons empty = 1 cons 2 cons 3 cons empty) : 0 = 1\n" +
      "\\lemma test2 (p : 1 cons 2 cons 3 cons empty = 1 cons 2 cons empty) : 0 = 1\n" +
      "\\lemma test3 (a : Array Nat 3) (p : 1 cons 2 cons empty = 1 cons 2 cons 3 cons a) : 0 = 1\n" +
      "\\lemma test4 (a : Array Nat 3) (p : 1 cons 2 cons 3 cons a = 1 cons 2 cons empty) : 0 = 1\n" +
      "\\lemma test5 (p : 1 cons 2 cons 3 cons empty = 1 cons 2 cons 4 cons empty) : 0 = 1\n" +
      "\\lemma test6 (a : Array Nat 3) (p : 1 cons 2 cons 3 cons a = 1 cons 3 cons 3 cons empty) : 0 = 1\n" +
      "\\lemma test7 (a b : Array Nat 3) (p : 1 cons 2 cons 3 cons a = 1 cons 2 cons 4 cons b) : 0 = 1\n" +
      "\\lemma test8 (a : Array Nat 0) (p : 1 cons 2 cons a = 1 cons 2 cons 3 cons empty) : 0 = 1\n" +
      "\\lemma test9 (a : Array Nat 0) (p : 1 cons 2 cons 3 cons empty = 1 cons 2 cons a) : 0 = 1");
  }

  @Test
  public void disjointConstructorsError() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test (a : Array Nat 1) (p : 1 cons 2 cons 3 cons empty = 1 cons 2 cons a) : 0 = 1", 1);
    assertThatErrorsAre(Matchers.missingClauses(1));
  }

  @Test
  public void disjointConstructorsError2() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test (a : Array Nat 1) (p : 1 cons 2 cons a = 1 cons 2 cons 3 cons empty) : 0 = 1", 1);
    assertThatErrorsAre(Matchers.missingClauses(1));
  }

  @Test
  public void disjointConstructorsError3() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test (a b : Array Nat 1) (p : 1 cons 2 cons a = 1 cons 2 cons b) : 0 = 1", 1);
    assertThatErrorsAre(Matchers.missingClauses(1));
  }

  @Test
  public void disjointConstructorsError4() {
    typeCheckModule(
      "\\open Array\n" +
      "\\lemma test (a : Array Nat 2) (p : 1 cons 2 cons a = 1 cons 2 cons 3 cons 4 cons empty) : 0 = 1", 1);
    assertThatErrorsAre(Matchers.missingClauses(1));
  }

  @Test
  public void patternMatchingTest() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f (x : Array Nat 2) : Nat\n" +
      "  | cons x (cons y empty) => x Nat.+ y\n" +
      "\\lemma test1 : f (cons 3 (cons 5 empty)) = 8 => idp\n" +
      "\\lemma test2 : f (\\new Array Nat 2 (\\lam _ => 6)) = 12 => idp");
  }

  @Test
  public void patternMatchingTest2() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f (x : Array Nat) : Nat\n" +
      "  | empty => 0\n" +
      "  | cons x empty => x\n" +
      "  | cons x (cons y _) => x Nat.+ y\n" +
      "\\lemma test1 : f (cons 7 (cons 12 (cons 22 empty))) = 19 => idp\n" +
      "\\lemma test2 : f (\\new Array Nat 5 (\\case __ \\with { | 0 => 41 | 1 => 56 | _ => 17 })) = 97 => idp");
  }

  @Test
  public void patternMatchingTest3() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f {n : Nat} (x : Array Nat n) : Nat \\elim n, x\n" +
      "  | 0, empty => 0\n" +
      "  | suc n, cons x a => x Nat.+ f a\n" +
      "\\lemma test1 : f (cons 7 (cons 22 (cons 46 empty))) = 75 => idp\n" +
      "\\lemma test2 : f (\\new Array Nat 4 (\\lam _ => 5)) = 20 => idp");
  }

  @Test
  public void patternMatchingTest4() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f {n : Nat} (x : Array Nat (suc (suc n))) : Nat\n" +
      "  | cons x (cons y _) => x Nat.+ y\n" +
      "\\lemma test1 : f (cons 3 (cons 5 empty)) = 8 => idp\n" +
      "\\lemma test2 : f (\\new Array Nat 2 (\\lam _ => 6)) = 12 => idp");
  }

  @Test
  public void tuplePatternTest() {
    typeCheckModule(
      "\\func test1 (x : Array) : \\Type\n" +
      "  | (A, _, _) => A\n" +
      "\\func test2 (x : Array) : Nat\n" +
      "  | (_, n, _) => n\n" +
      "\\func test3 (x : Array) : Fin x.len -> x.A\n" +
      "  | (_, _, f) => f\n" +
      "\\func test4 {A : \\Type} (x : Array A) : Nat\n" +
      "  | (n, _) => n\n" +
      "\\func test5 {A : \\Type} (x : Array A) : Fin x.len -> A\n" +
      "  | (_, f) => f\n" +
      "\\func test6 {n : Nat} (x : Array { | len => n }) : \\Type\n" +
      "  | (A, _) => A\n" +
      "\\func test7 {n : Nat} (x : Array { | len => n }) : Fin n -> x.A\n" +
      "  | (_, f) => f");
  }

  @Test
  public void patternMatchingError() {
    typeCheckDef("\\func f (x : Array Nat) : Nat", 1);
    assertThatErrorsAre(Matchers.missingClauses(2));
  }

  @Test
  public void extractType() {
    typeCheckModule(
      "\\open Array\n" +
      "\\func f (x : Array) : \\Type\n" +
      "  | empty {A} => A\n" +
      "  | cons {A} _ _ => A\n" +
      "\\func test : f (cons 1 empty) = Nat => idp");
  }

  @Test
  public void goalTest() {
    Definition def = typeCheckDef("\\func test (n : Nat) : Array Nat (suc n) => {?} Array.cons {?}", 2);
    Map<ClassField, Expression> impls = new HashMap<>();
    impls.put(Prelude.ARRAY_ELEMENTS_TYPE, Nat());
    impls.put(Prelude.ARRAY_LENGTH, new ReferenceExpression(def.getParameters()));
    assertThatErrorsAre(Matchers.goal(1), Matchers.goal(new ClassCallExpression(Prelude.ARRAY, Sort.SET0, impls, Sort.SET0, UniverseKind.NO_UNIVERSES)));
  }

  @Test
  public void goalTest2() {
    typeCheckDef("\\func test : Array Nat 7 => {?} Array.cons {?} Array.cons {?}", 3);
    Map<ClassField, Expression> impls = new HashMap<>();
    impls.put(Prelude.ARRAY_ELEMENTS_TYPE, Nat());
    impls.put(Prelude.ARRAY_LENGTH, new SmallIntegerExpression(5));
    assertThatErrorsAre(Matchers.goal(0), Matchers.goal(0), Matchers.goal(new ClassCallExpression(Prelude.ARRAY, Sort.SET0, impls, Sort.SET0, UniverseKind.NO_UNIVERSES)));
  }

  @Test
  public void consTypeTest() {
    typeCheckDef("\\func test : Array (Fin 2) => 0 Array.cons 1 Array.cons Array.empty");
  }

  @Test
  public void coerceTest() {
    FunctionDefinition def = (FunctionDefinition) typeCheckDef("\\func test (a : Array Nat 2) (i : Fin 2) => a i");
    assertTrue(def.getBody() instanceof FunCallExpression && ((FunCallExpression) def.getBody()).getDefinition() == Prelude.ARRAY_INDEX);
  }
}
