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
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ElementHashCalculator extends TreePathScanner<Void, HashContext> {

    private final Trees trees;
    private final Renames renames;
    private final Deque<Set<String>> localScopeStack = new ArrayDeque<>();
    /** Greater than zero while scanning method bodies, field initializers and initializer blocks. */
    private int bodyDepth;

    /**
     * Creates a new MemberShaCalculator with required Trees utility for attributed FQN resolution.
     *
     * @param trees Trees utility instance
     */
    public ElementHashCalculator(Trees trees) {
        this(trees, Renames.EMPTY);
    }

    /**
     * Creates a calculator which, for the {@link ChangeCategory#WITHOUT_RENAMES} category, hashes the old
     * names of renamed elements: in their declarations, in every reference javac resolved to them and in
     * the qualified names of types. This is meant to scan the new version of a file; the old version is
     * scanned without renames.
     *
     * @param trees   Trees utility instance
     * @param renames the renamed elements
     */
    public ElementHashCalculator(Trees trees, Renames renames) {
        if (trees == null) {
            throw new IllegalArgumentException("Trees must not be null");
        }
        this.trees = trees;
        this.renames = renames;
    }

    /** The old name of the element if it was renamed, else its current name. */
    private String oldName(Element element, String name) {
        return renames.isEmpty() ? name : renames.oldName(ElementIds.of(element), name);
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
        Element el = trees.getElement(getCurrentPath());
        typeNode.setElementId(ElementIds.of(el));
        updateAllCategories(context, "KIND:" + node.getKind());
        if (node.getSimpleName() != null) {
            updateAttribute(typeNode, "NAME:" + node.getSimpleName(), "NAME:" + oldName(el, className));
        }

        if (el instanceof TypeElement typeEl) {
            updateAllCategories(context, "FQN:" + typeEl.getQualifiedName());
            updateTypeParams(typeNode, typeEl.getTypeParameters());
        }
        updateCommonFacets(typeNode, className, node.getModifiers());

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
            Element el = trees.getElement(getCurrentPath());
            methodNode.setElementId(ElementIds.of(el));
            updateAllCategories(context, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAttribute(methodNode, "NAME:" + node.getName(), "NAME:" + oldName(el, methodName));
            }

            updateCommonFacets(methodNode, methodName, node.getModifiers());
            if (el instanceof ExecutableElement execEl) {
                updateAllCategories(context, "RETURN_TYPE:" + execEl.getReturnType());
                methodNode.updateFacet(Facet.RETURN_TYPE, String.valueOf(execEl.getReturnType()));
                for (var param : execEl.getParameters()) {
                    updateAllCategories(context, "PARAM_TYPE:" + param.asType());
                    methodNode.updateFacet(Facet.PARAM_TYPES, String.valueOf(param.asType()));
                }
                methodNode.setParamCount(execEl.getParameters().size());
                updateTypeParams(methodNode, execEl.getTypeParameters());
                execEl.getThrownTypes().stream().map(String::valueOf).sorted()
                        .forEach(t -> methodNode.updateFacet(Facet.THROWS, t));
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
            Element el = trees.getElement(getCurrentPath());
            varNode.setElementId(ElementIds.of(el));
            updateAttribute(varNode, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAttribute(varNode, "NAME:" + node.getName(), "NAME:" + oldName(el, varName));
            }

            updateCommonFacets(varNode, varName, node.getModifiers());
            if (el instanceof VariableElement varEl) {
                updateAttribute(varNode, "TYPE:" + varEl.asType());
                varNode.updateFacet(Facet.RETURN_TYPE, String.valueOf(varEl.asType()));
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
                if (category == ChangeCategory.WITHOUT_RENAMES && !isLocalOrParam && name != null) {
                    category.onAttribute(category, current, "KIND:" + node.getKind(), region());
                    category.onAttribute(category, current,
                            "IDENTIFIER:" + oldName(trees.getElement(getCurrentPath()), name.toString()), region());
                } else {
                    category.onIdentifier(category, current, node, isLocalOrParam, region());
                }
            }
        }
        return super.visitIdentifier(node, context);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, HashContext context) {
        updateSelector(node.getKind(), node.getIdentifier(), context);
        return super.visitMemberSelect(node, context);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree node, HashContext context) {
        updateSelector(node.getKind(), node.getName(), context);
        return super.visitMemberReference(node, context);
    }

    /** Hashes the name after the dot of a member select, like the name of an identifier. */
    private void updateSelector(Tree.Kind kind, Name name, HashContext context) {
        ElementInfo current = context.getCurrentNode();
        if (current != null && name != null) {
            updateAttribute(current, "KIND:" + kind);
            updateAttribute(current, "IDENTIFIER:" + name,
                    "IDENTIFIER:" + oldName(trees.getElement(getCurrentPath()), name.toString()));
        }
    }

    @Override
    public Void visitModifiers(ModifiersTree node, HashContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        updateAllCategories(context, "MODIFIERS:" + node.getFlags());
        return super.visitModifiers(node, context);
    }

    private static void updateCommonFacets(ElementInfo info, String name, ModifiersTree modifiers) {
        info.updateFacet(Facet.NAME, name);
        info.updateFacet(Facet.MODIFIERS, String.valueOf(modifiers.getFlags()));
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            info.updateFacet(Facet.ANNOTATIONS, annotation.toString());
        }
    }

    private static void updateTypeParams(ElementInfo info, List<? extends TypeParameterElement> typeParams) {
        for (TypeParameterElement tp : typeParams) {
            info.updateFacet(Facet.TYPE_PARAMS, tp + ":" + tp.getBounds());
        }
    }

    private void updateAllCategories(HashContext context, String attribute) {
        if (context.getCurrentNode() != null) {
            updateAttribute(context.getCurrentNode(), attribute);
        }
    }

    private void updateAttribute(ElementInfo node, String attribute) {
        updateAttribute(node, attribute, null);
    }

    /**
     * Updates the digests of all categories.
     *
     * @param withoutRenames what goes into {@link ChangeCategory#WITHOUT_RENAMES} instead of the attribute,
     *                       {@code null} to use the attribute with the old qualified names of types
     */
    private void updateAttribute(ElementInfo node, String attribute, String withoutRenames) {
        for (ChangeCategory category : ChangeCategory.values()) {
            String text = attribute;
            if (category == ChangeCategory.WITHOUT_RENAMES) {
                text = withoutRenames != null ? withoutRenames
                        : attribute.startsWith("LITERAL:") ? attribute : renames.undoTypeNames(attribute);
            }
            category.onAttribute(category, node, text, region());
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
