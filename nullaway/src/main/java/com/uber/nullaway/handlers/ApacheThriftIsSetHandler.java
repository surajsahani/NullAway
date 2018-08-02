/*
 * Copyright (c) 2018 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.javacutil.Pair;

/**
 * Handler to better handle {@code isSetXXXX()} methods in code generated by Apache Thrift. With
 * this handler, we learn appropriate nullability facts about the relevant property from these
 * calls.
 */
public class ApacheThriftIsSetHandler extends BaseNoOpHandler {

  private static String TBASE_NAME = "org.apache.thrift.TBase";

  @Nullable private Optional<Type> tbaseType;

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    if (tbaseType == null) {
      tbaseType =
          Optional.ofNullable(state.getTypeFromString(TBASE_NAME)).map(state.getTypes()::erasure);
    }
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(node.getTree());
    if (thriftIsSetCall(symbol, types)) {
      String methodName = symbol.getSimpleName().toString();
      // remove "isSet"
      String capPropName = methodName.substring(5);
      if (capPropName.length() > 0) {
        // build access paths for the getter and the field access, and
        // make them nonnull in the thenUpdates
        Pair<Element, Element> fieldAndGetter = getFieldAndSetterForProperty(symbol, capPropName);
        Node base = node.getTarget().getReceiver();
        updateNonNullAPsForElement(thenUpdates, fieldAndGetter.first, base);
        updateNonNullAPsForElement(thenUpdates, fieldAndGetter.second, base);
      }
    }
    return NullnessHint.UNKNOWN;
  }

  private void updateNonNullAPsForElement(
      AccessPathNullnessPropagation.Updates updates, @Nullable Element elem, Node base) {
    if (elem != null) {
      AccessPath ap = AccessPath.fromBaseAndElement(base, elem);
      if (ap != null) {
        updates.set(ap, Nullness.NONNULL);
      }
    }
  }

  /**
   * Returns the field (if it exists and is visible) and the setter for a property. If the field is
   * not available, the first element of the returned pair is {@code null}.
   */
  private Pair<Element, Element> getFieldAndSetterForProperty(
      Symbol.MethodSymbol symbol, String capPropName) {
    Element field = null;
    Element getter = null;
    String fieldName = decapitalize(capPropName);
    String getterName = "get" + capPropName;
    for (Symbol elem : symbol.owner.getEnclosedElements()) {
      if (elem.getKind().isField() && elem.getSimpleName().toString().equals(fieldName)) {
        if (field != null) {
          throw new RuntimeException("already found field " + fieldName);
        }
        field = elem;
      } else if (elem.getKind().equals(ElementKind.METHOD)
          && elem.getSimpleName().toString().equals(getterName)) {
        if (getter != null) {
          throw new RuntimeException("already found getter " + getterName);
        }
        getter = elem;
      }
    }
    if (field != null && field.asType().getKind().isPrimitive()) {
      // ignore primitive properties
      return Pair.of(null, null);
    }
    return Pair.of(field, getter);
  }

  private static String decapitalize(String str) {
    // assumes str is non-null and non-empty
    char c[] = str.toCharArray();
    c[0] = Character.toLowerCase(c[0]);
    return new String(c);
  }

  private boolean thriftIsSetCall(Symbol.MethodSymbol symbol, Types types) {
    Preconditions.checkNotNull(tbaseType);
    // noinspection ConstantConditions
    return tbaseType.isPresent()
        && symbol.getSimpleName().toString().startsWith("isSet")
        // weeds out the isSet() method in TBase itself
        && symbol.getParameters().length() == 0
        && types.isSubtype(symbol.owner.type, tbaseType.get());
  }
}
