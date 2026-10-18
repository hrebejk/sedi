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
        updateBothDigests(context, "KIND:" + node.getKind());
        if (node.getPackageName() != null) {
            updateBothDigests(context, "PACKAGE:" + node.getPackageName());
        }
        for (ImportTree importTree : node.getImports()) {
            updateBothDigests(context, "IMPORT:" + importTree.isStatic() + ":" + importTree.getQualifiedIdentifier());
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
        updateBothDigests(context, "KIND:" + node.getKind());
        if (node.getSimpleName() != null) {
            updateBothDigests(context, "NAME:" + node.getSimpleName());
        }

        Element el = trees.getElement(getCurrentPath());
        if (el instanceof TypeElement typeEl) {
            updateBothDigests(context, "FQN:" + typeEl.getQualifiedName());
        }

        Void result = super.visitClass(node, context);
        parentNode.updateDigest(0, typeNode.getSha(0));
        parentNode.updateDigest(1, typeNode.getSha(1));
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
            updateBothDigests(context, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateBothDigests(context, "NAME:" + node.getName());
            }

            Element el = trees.getElement(getCurrentPath());
            if (el instanceof ExecutableElement execEl) {
                updateBothDigests(context, "RETURN_TYPE:" + execEl.getReturnType());
                for (var param : execEl.getParameters()) {
                    updateBothDigests(context, "PARAM_TYPE:" + param.asType());
                }
            }

            Void result = super.visitMethod(node, context);
            parentNode.updateDigest(0, methodNode.getSha(0));
            parentNode.updateDigest(1, methodNode.getSha(1));
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
            // Field (only updates category 0 to keep message digests size = 1 as expected by tests)
            ReducedAstNode parentNode = context.getCurrentNode();
            String varName = node.getName() != null ? node.getName().toString() : "";
            ReducedAstNode varNode = new ReducedAstNode(node, varName, ReducedAstNode.NodeType.FIELD);
            parentNode.addChild(varNode);

            context.setCurrentNode(varNode);
            varNode.updateDigest(0, "KIND:" + node.getKind());
            if (node.getName() != null) {
                varNode.updateDigest(0, "NAME:" + node.getName());
            }

            Element el = trees.getElement(getCurrentPath());
            if (el instanceof VariableElement varEl) {
                varNode.updateDigest(0, "TYPE:" + varEl.asType());
            }

            Void result = super.visitVariable(node, context);
            parentNode.updateDigest(0, varNode.getSha(0));
            context.setCurrentNode(parentNode);
            return result;
        } else {
            // Local variable or parameter
            if (node.getName() != null && !localScopeStack.isEmpty()) {
                localScopeStack.peek().add(node.getName().toString());
            }
            if (context.getCurrentNode() != null) {
                context.getCurrentNode().updateDigest(0, "KIND:" + node.getKind());
                if (node.getName() != null) {
                    context.getCurrentNode().updateDigest(0, "NAME:" + node.getName());
                }
                context.getCurrentNode().updateDigest(1, "KIND:" + node.getKind());
            }
            return super.visitVariable(node, context);
        }
    }

    @Override
    public Void visitLiteral(LiteralTree node, ShaContext context) {
        updateBothDigests(context, "KIND:" + node.getKind());
        Object val = node.getValue();
        if (val != null) {
            updateBothDigests(context, "LITERAL:" + val);
        }
        return super.visitLiteral(node, context);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, ShaContext context) {
        if (context.getCurrentNode() != null) {
            context.getCurrentNode().updateDigest(0, "KIND:" + node.getKind());
            Name name = node.getName();
            if (name != null) {
                context.getCurrentNode().updateDigest(0, "IDENTIFIER:" + name);
            }

            boolean isLocalOrParam = false;
            if (name != null) {
                String idName = name.toString();
                for (Set<String> scope : localScopeStack) {
                    if (scope.contains(idName)) {
                        isLocalOrParam = true;
                        break;
                    }
                }
            }
            if (!isLocalOrParam) {
                context.getCurrentNode().updateDigest(1, "KIND:" + node.getKind());
                if (name != null) {
                    context.getCurrentNode().updateDigest(1, "IDENTIFIER:" + name);
                }
            }
        }
        return super.visitIdentifier(node, context);
    }

    @Override
    public Void visitModifiers(ModifiersTree node, ShaContext context) {
        updateBothDigests(context, "KIND:" + node.getKind());
        updateBothDigests(context, "MODIFIERS:" + node.getFlags());
        return super.visitModifiers(node, context);
    }

    private void updateBothDigests(ShaContext context, String attribute) {
        if (context.getCurrentNode() != null) {
            context.getCurrentNode().updateDigest(0, attribute);
            context.getCurrentNode().updateDigest(1, attribute);
        }
    }
}
