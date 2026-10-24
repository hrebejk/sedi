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

public class ElementHashCalculator extends TreePathScanner<Void, HashContext> {

    private final Trees trees;
    private final Deque<Set<String>> localScopeStack = new ArrayDeque<>();
    /** Greater than zero while scanning method bodies, field initializers and initializer blocks. */
    private int bodyDepth;

    /**
     * Creates a new MemberShaCalculator with required Trees utility for attributed FQN resolution.
     *
     * @param trees Trees utility instance
     */
    public ElementHashCalculator(Trees trees) {
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
    public ElementInfo calculate(CompilationUnitTree compilationUnit) {
        if (compilationUnit == null) {
            return new ElementInfo(null, "ROOT", ElementInfo.NodeType.COMPILATION_UNIT);
        }

        ElementInfo rootNode = new ElementInfo(
                compilationUnit,
                compilationUnit.getPackageName() != null ? compilationUnit.getPackageName().toString() : "default",
                ElementInfo.NodeType.COMPILATION_UNIT
        );

        HashContext context = new HashContext(rootNode);
        scan(compilationUnit, context);
        return rootNode;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree node, HashContext context) {
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
    public Void visitClass(ClassTree node, HashContext context) {
        ElementInfo parentNode = context.getCurrentNode();
        String className = node.getSimpleName() != null ? node.getSimpleName().toString() : "";
        ElementInfo typeNode = new ElementInfo(node, className, ElementInfo.NodeType.TYPE);
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
    public Void visitMethod(MethodTree node, HashContext context) {
        localScopeStack.push(new HashSet<>());
        try {
            for (var param : node.getParameters()) {
                if (param.getName() != null) {
                    localScopeStack.peek().add(param.getName().toString());
                }
            }

            ElementInfo parentNode = context.getCurrentNode();
            String methodName = node.getName() != null ? node.getName().toString() : "";
            ElementInfo methodNode = new ElementInfo(node, methodName, ElementInfo.NodeType.METHOD);
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

            scan(node.getModifiers(), context);
            scan(node.getReturnType(), context);
            scan(node.getTypeParameters(), context);
            scan(node.getParameters(), context);
            scan(node.getReceiverParameter(), context);
            scan(node.getThrows(), context);
            scan(node.getDefaultValue(), context);
            scanBody(node.getBody(), context);
            cascade(parentNode, methodNode);
            context.setCurrentNode(parentNode);
            return null;
        } finally {
            localScopeStack.pop();
        }
    }

    @Override
    public Void visitVariable(VariableTree node, HashContext context) {
        TreePath path = getCurrentPath();
        if (path != null && path.getParentPath() != null && path.getParentPath().getLeaf() instanceof ClassTree) {
            // Field
            ElementInfo parentNode = context.getCurrentNode();
            String varName = node.getName() != null ? node.getName().toString() : "";
            ElementInfo varNode = new ElementInfo(node, varName, ElementInfo.NodeType.FIELD);
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

            scan(node.getModifiers(), context);
            scan(node.getType(), context);
            scan(node.getNameExpression(), context);
            scanBody(node.getInitializer(), context);
            cascade(parentNode, varNode);
            context.setCurrentNode(parentNode);
            return null;
        } else {
            // Local variable or parameter
            if (node.getName() != null && !localScopeStack.isEmpty()) {
                localScopeStack.peek().add(node.getName().toString());
            }
            if (context.getCurrentNode() != null) {
                for (ChangeCategory category : ChangeCategory.values()) {
                    category.onLocalVariable(category, context.getCurrentNode(), node, region());
                }
            }
            return super.visitVariable(node, context);
        }
    }

    @Override
    public Void visitBlock(BlockTree node, HashContext context) {
        Tree parent = getCurrentPath().getParentPath() != null ? getCurrentPath().getParentPath().getLeaf() : null;
        if (parent instanceof ClassTree) {
            // Initializer block
            bodyDepth++;
            try {
                return super.visitBlock(node, context);
            } finally {
                bodyDepth--;
            }
        }
        return super.visitBlock(node, context);
    }

    @Override
    public Void visitLiteral(LiteralTree node, HashContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        Object val = node.getValue();
        if (val != null) {
            updateAllCategories(context, "LITERAL:" + val);
        }
        return super.visitLiteral(node, context);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, HashContext context) {
        ElementInfo current = context.getCurrentNode();
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
            for (ChangeCategory category : ChangeCategory.values()) {
                category.onIdentifier(category, current, node, isLocalOrParam, region());
            }
        }
        return super.visitIdentifier(node, context);
    }

    @Override
    public Void visitModifiers(ModifiersTree node, HashContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        updateAllCategories(context, "MODIFIERS:" + node.getFlags());
        return super.visitModifiers(node, context);
    }

    private void updateAllCategories(HashContext context, String attribute) {
        if (context.getCurrentNode() != null) {
            updateAttribute(context.getCurrentNode(), attribute);
        }
    }

    private void updateAttribute(ElementInfo node, String attribute) {
        for (ChangeCategory category : ChangeCategory.values()) {
            category.onAttribute(category, node, attribute, region());
        }
    }

    private HashCalculation.Region region() {
        return bodyDepth > 0 ? HashCalculation.Region.BODY : HashCalculation.Region.SIGNATURE;
    }

    private void scanBody(Tree body, HashContext context) {
        bodyDepth++;
        try {
            scan(body, context);
        } finally {
            bodyDepth--;
        }
    }

    private static void cascade(ElementInfo parent, ElementInfo child) {
        for (ChangeCategory category : ChangeCategory.values()) {
            category.onChildCompleted(category, parent, child);
        }
    }
}
