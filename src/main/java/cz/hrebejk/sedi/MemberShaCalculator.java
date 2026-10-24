/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Calculates SHA hashes for types and members by dynamically building the reduced AST
 * during visitor traversal using ShaContext and attributed AST type information (Trees),
 * incorporating FQNs, primitive types, packages, and imports without toString().
 */
public class MemberShaCalculator extends TreePathScanner<Void, ShaContext> {

    private final Trees trees;
    private final Deque<Set<String>> localScopeStack = new ArrayDeque<>();

    /**
     * Creates a new MemberShaCalculator with required Trees utility for attributed FQN resolution.
     *
     * @param trees Trees utility instance
     */
    public MemberShaCalculator(Trees trees) {
        if (trees == null) {
            throw new IllegalArgumentException("Trees must not be null");
        }
        this.trees = trees;
    }

    /**
     * Scans the compilation unit, dynamically building the reduced AST and computing SHAs
     * with upward digest cascading and type FQN incorporation.
     *
     * @param compilationUnit the CompilationUnitTree to analyze
     * @return the root ReducedAstNode
     */
    public ReducedAstNode calculate(CompilationUnitTree compilationUnit) {
        if (compilationUnit == null) {
            return new ReducedAstNode(null, "ROOT", ReducedAstNode.NodeType.COMPILATION_UNIT);
        }

        ReducedAstNode rootNode = new ReducedAstNode(
                compilationUnit,
                compilationUnit.getPackageName() != null ? compilationUnit.getPackageName().toString() : "default",
                ReducedAstNode.NodeType.COMPILATION_UNIT
        );

        ShaContext context = new ShaContext(rootNode);
        scan(compilationUnit, context);
        return rootNode;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree node, ShaContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        if (node.getPackageName() != null) {
            updateAllCategories(context, "PACKAGE:" + node.getPackageName());
        }
        for (ImportTree importTree : node.getImports()) {
            updateAllCategories(context, "IMPORT:" + importTree.isStatic() + ":" + importTree.getQualifiedIdentifier());
        }
        return super.visitCompilationUnit(node, context);
    }

    @Override
    public Void visitClass(ClassTree node, ShaContext context) {
        ReducedAstNode parentNode = context.getCurrentNode();
        String className = node.getSimpleName() != null ? node.getSimpleName().toString() : "";
        ReducedAstNode typeNode = new ReducedAstNode(node, className, ReducedAstNode.NodeType.TYPE);
        parentNode.addChild(typeNode);

        context.setCurrentNode(typeNode);
        updateAllCategories(context, "KIND:" + node.getKind());
        if (node.getSimpleName() != null) {
            updateAllCategories(context, "NAME:" + node.getSimpleName());
        }

        Element el = trees.getElement(getCurrentPath());
        if (el instanceof TypeElement typeEl) {
            updateAllCategories(context, "FQN:" + typeEl.getQualifiedName());
        }

        Void result = super.visitClass(node, context);
        cascade(parentNode, typeNode);
        context.setCurrentNode(parentNode);
        return result;
    }

    @Override
    public Void visitMethod(MethodTree node, ShaContext context) {
        localScopeStack.push(new HashSet<>());
        try {
            for (var param : node.getParameters()) {
                if (param.getName() != null) {
                    localScopeStack.peek().add(param.getName().toString());
                }
            }

            ReducedAstNode parentNode = context.getCurrentNode();
            String methodName = node.getName() != null ? node.getName().toString() : "";
            ReducedAstNode methodNode = new ReducedAstNode(node, methodName, ReducedAstNode.NodeType.METHOD);
            parentNode.addChild(methodNode);

            context.setCurrentNode(methodNode);
            updateAllCategories(context, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAllCategories(context, "NAME:" + node.getName());
            }

            Element el = trees.getElement(getCurrentPath());
            if (el instanceof ExecutableElement execEl) {
                updateAllCategories(context, "RETURN_TYPE:" + execEl.getReturnType());
                for (var param : execEl.getParameters()) {
                    updateAllCategories(context, "PARAM_TYPE:" + param.asType());
                }
            }

            Void result = super.visitMethod(node, context);
            cascade(parentNode, methodNode);
            context.setCurrentNode(parentNode);
            return result;
        } finally {
            localScopeStack.pop();
        }
    }

    @Override
    public Void visitVariable(VariableTree node, ShaContext context) {
        TreePath path = getCurrentPath();
        if (path != null && path.getParentPath() != null && path.getParentPath().getLeaf() instanceof ClassTree) {
            // Field
            ReducedAstNode parentNode = context.getCurrentNode();
            String varName = node.getName() != null ? node.getName().toString() : "";
            ReducedAstNode varNode = new ReducedAstNode(node, varName, ReducedAstNode.NodeType.FIELD);
            parentNode.addChild(varNode);

            context.setCurrentNode(varNode);
            updateAttribute(varNode, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAttribute(varNode, "NAME:" + node.getName());
            }

            Element el = trees.getElement(getCurrentPath());
            if (el instanceof VariableElement varEl) {
                updateAttribute(varNode, "TYPE:" + varEl.asType());
            }

            Void result = super.visitVariable(node, context);
            cascade(parentNode, varNode);
            context.setCurrentNode(parentNode);
            return result;
        } else {
            // Local variable or parameter
            if (node.getName() != null && !localScopeStack.isEmpty()) {
                localScopeStack.peek().add(node.getName().toString());
            }
            if (context.getCurrentNode() != null) {
                for (ShaCategory category : ShaCategory.values()) {
                    category.onLocalVariable(category, context.getCurrentNode(), node);
                }
            }
            return super.visitVariable(node, context);
        }
    }

    @Override
    public Void visitLiteral(LiteralTree node, ShaContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        Object val = node.getValue();
        if (val != null) {
            updateAllCategories(context, "LITERAL:" + val);
        }
        return super.visitLiteral(node, context);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, ShaContext context) {
        ReducedAstNode current = context.getCurrentNode();
        if (current != null) {
            boolean isLocalOrParam = false;
            Name name = node.getName();
            if (name != null) {
                String idName = name.toString();
                for (Set<String> scope : localScopeStack) {
                    if (scope.contains(idName)) {
                        isLocalOrParam = true;
                        break;
                    }
                }
            }
            for (ShaCategory category : ShaCategory.values()) {
                category.onIdentifier(category, current, node, isLocalOrParam);
            }
        }
        return super.visitIdentifier(node, context);
    }

    @Override
    public Void visitModifiers(ModifiersTree node, ShaContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        updateAllCategories(context, "MODIFIERS:" + node.getFlags());
        return super.visitModifiers(node, context);
    }

    private void updateAllCategories(ShaContext context, String attribute) {
        if (context.getCurrentNode() != null) {
            updateAttribute(context.getCurrentNode(), attribute);
        }
    }

    private static void updateAttribute(ReducedAstNode node, String attribute) {
        for (ShaCategory category : ShaCategory.values()) {
            category.onAttribute(category, node, attribute);
        }
    }

    private static void cascade(ReducedAstNode parent, ReducedAstNode child) {
        for (ShaCategory category : ShaCategory.values()) {
            parent.updateDigest(category, child.getSha(category));
        }
    }
}
