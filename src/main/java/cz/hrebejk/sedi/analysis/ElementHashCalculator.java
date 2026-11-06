/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ElementHashCalculator extends TreePathScanner<Void, HashContext> {

    private final Trees trees;
    private final Renames renames;
    private final Deque<Set<String>> localScopeStack = new ArrayDeque<>();
    /** Greater than zero while scanning method bodies, field initializers and initializer blocks. */
    private int bodyDepth;
    /**
     * Trees javac adds to enum constants, not in the source and always naming the enum: the type of the constant,
     * the class of its {@code new} and the superclass of its body. They are not hashed.
     */
    private final Set<Tree> synthesized = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /**
     * Greater than zero while scanning inside a {@link #reference reference}: the categories which undo renamed
     * references already have the element it refers to and get nothing more.
     */
    private int referenceDepth;
    /** The unit being scanned, its source and the positions of its trees, to find the lines of the elements. */
    private CompilationUnitTree unit;
    private String source = "";
    private SourceText sourceText;

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

        unit = compilationUnit;
        try {
            source = compilationUnit.getSourceFile().getCharContent(true).toString();
        } catch (java.io.IOException e) {
            source = "";
        }
        sourceText = new SourceText(compilationUnit, trees.getSourcePositions(), source);
        HashContext context = new HashContext(rootNode);
        scan(compilationUnit, context);
        return rootNode;
    }

    /**
     * Puts every tree into the digests of the member being scanned: its kind, whatever else identifies it
     * (see {@link #identity}), then its children, then an end marker. The markers make the sequence a
     * bracketed one, so different nesting of the same trees gives a different hash. An absent optional child
     * (such as the expression of {@code return;}) is marked too, so it can't be mistaken for another child.
     * Types, methods and fields are left out: they are elements with digests of their own.
     */
    @Override
    public Void scan(Tree tree, HashContext context) {
        if (tree != null && synthesized.contains(tree)) {
            return null;
        }
        ElementInfo current = context.getCurrentNode();
        if (current == null) {
            return super.scan(tree, context);
        }
        if (tree == null) {
            updateAttribute(current, "NULL");
            return null;
        }
        if (isElement(tree)) {
            return super.scan(tree, context);
        }
        if (isUnresolved(tree)) {
            current.getUnresolved().add(tree);
        }
        String reference = referenceDepth == 0 ? reference(tree) : null;
        // An initializer block is a part of the body of its type, not of the signature.
        boolean initializer = tree instanceof BlockTree && getCurrentPath() != null
                && getCurrentPath().getLeaf() instanceof ClassTree;
        if (initializer) {
            bodyDepth++;
        }
        // The nodes of bodies of methods and fields are kept with their hashes, to diff the bodies, and so are the
        // nodes of the signatures of methods, fields and types, to diff the signatures.
        boolean member = current.getNodeType() == ElementInfo.NodeType.METHOD
                || current.getNodeType() == ElementInfo.NodeType.FIELD;
        boolean node = referenceDepth == 0
                && (bodyDepth > 0 ? member : member || current.getNodeType() == ElementInfo.NodeType.TYPE);
        if (node) {
            current.openNode(new TreePath(getCurrentPath(), tree),
                    bodyDepth > 0 ? ChangeCategory.BODY : ChangeCategory.SIGNATURE);
        }
        try {
            if (reference != null) {
                for (ChangeCategory category : ChangeCategory.values()) {
                    if (category.undoesRenamedReferences()) {
                        category.onAttribute(category, current, "REFERENCE:" + reference, region());
                    }
                }
                referenceDepth++;
            }
            updateAttribute(current, "KIND:" + tree.getKind());
            String identity = identity(tree);
            if (identity != null) {
                updateAttribute(current, "TREE:" + identity);
            }
            Void result = super.scan(tree, context);
            updateAttribute(current, "END:" + tree.getKind());
            return result;
        } finally {
            if (reference != null) {
                referenceDepth--;
            }
            if (node) {
                current.closeNode();
            }
            if (initializer) {
                bodyDepth--;
            }
        }
    }

    /**
     * Marks the length of every list of children (parameters, arguments, cases, ...) so a child can't move
     * from one list to the next unnoticed. The members of a type and the types of a file are not marked: they
     * are elements and do not belong to the signature of their parent.
     */
    @Override
    public Void scan(Iterable<? extends Tree> nodes, HashContext context) {
        ElementInfo current = context.getCurrentNode();
        if (current != null && !isElementList(nodes)) {
            int size = 0;
            if (nodes != null) {
                for (Tree ignored : nodes) {
                    size++;
                }
            }
            updateAttribute(current, nodes == null ? "NULL" : "LIST:" + size);
        }
        return super.scan(nodes, context);
    }

    private boolean isElementList(Iterable<? extends Tree> nodes) {
        Tree owner = getCurrentPath() == null ? null : getCurrentPath().getLeaf();
        return owner instanceof ClassTree c && c.getMembers() == nodes
                || owner instanceof CompilationUnitTree u && u.getTypeDecls() == nodes;
    }

    private boolean isElement(Tree tree) {
        if (tree instanceof ClassTree || tree instanceof MethodTree || tree instanceof CompilationUnitTree) {
            return true;
        }
        if (tree instanceof VariableTree) {
            TreePath parent = getCurrentPath();
            return parent != null && parent.getLeaf() instanceof ClassTree;
        }
        return false;
    }

    /**
     * The element a name refers to, if it does not matter how the name is written: a type, a package or a static
     * member, referred to by its simple name ({@code List}, {@code max} imported statically) or qualified by the
     * name of a type or package ({@code java.util.List}, {@code Math.max}). The categories which undo renamed
     * references hash only this element, so changing such a name from a qualified to a simple one or back is
     * cosmetic.
     *
     * @return the qualified name of the element with renames undone, {@code null} if the tree is not such a name
     */
    private String reference(Tree tree) {
        if (!(tree instanceof IdentifierTree || tree instanceof MemberSelectTree) || getCurrentPath() == null) {
            return null;
        }
        TreePath path = new TreePath(getCurrentPath(), tree);
        Element element = trees.getElement(path);
        if (element == null || element.asType().getKind() == javax.lang.model.type.TypeKind.ERROR) {
            return null;
        }
        if (tree instanceof MemberSelectTree select
                && !isTypeOrPackage(trees.getElement(new TreePath(path, select.getExpression())))) {
            return null;
        }
        if (isTypeOrPackage(element)) {
            return element instanceof TypeElement type
                    ? renames.undoTypeNames(type.getQualifiedName().toString())
                    : element.toString();
        }
        boolean member = switch (element.getKind()) {
            case FIELD, ENUM_CONSTANT, METHOD -> element.getModifiers().contains(Modifier.STATIC);
            default -> false;
        };
        String owner = member ? ElementIds.of(element.getEnclosingElement()) : null;
        return owner == null ? null
                : renames.undoTypeNames(owner) + "#" + oldName(element, element.getSimpleName().toString());
    }

    /** Whether the tree is a name javac could not resolve, or code it could not parse. */
    private boolean isUnresolved(Tree tree) {
        if (tree instanceof ErroneousTree) {
            return true;
        }
        if (!(tree instanceof IdentifierTree || tree instanceof MemberSelectTree) || getCurrentPath() == null) {
            return false;
        }
        Element element = trees.getElement(new TreePath(getCurrentPath(), tree));
        return element == null || element.asType().getKind() == javax.lang.model.type.TypeKind.ERROR;
    }

    private static boolean isTypeOrPackage(Element element) {
        return element instanceof TypeElement || element != null && element.getKind() == ElementKind.PACKAGE;
    }

    /**
     * What identifies a tree besides its kind and children, for the trees whose kind alone does not say
     * (names are hashed by {@code visitIdentifier} and the like): the primitive type, labels, names of type
     * parameters, the form of a case, static initializers, the mode of a method reference, flags of module
     * directives.
     */
    private static String identity(Tree tree) {
        return switch (tree) {
            case PrimitiveTypeTree t -> t.getPrimitiveTypeKind().toString();
            case LabeledStatementTree t -> t.getLabel().toString();
            case BreakTree t -> String.valueOf(t.getLabel());
            case ContinueTree t -> String.valueOf(t.getLabel());
            case TypeParameterTree t -> t.getName().toString();
            case CaseTree t -> t.getCaseKind().toString();
            case LambdaExpressionTree t -> t.getBodyKind().toString();
            case BlockTree t -> String.valueOf(t.isStatic());
            case MemberReferenceTree t -> t.getMode().toString();
            case ImportTree t -> String.valueOf(t.isStatic());
            case ModuleTree t -> t.getModuleType().toString();
            case RequiresTree t -> t.isStatic() + ":" + t.isTransitive();
            default -> null;
        };
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
        typeNode.setJavadoc(trees.getDocComment(getCurrentPath()));
        setLines(typeNode, node, node.getModifiers());
        updateAllCategories(context, "KIND:" + node.getKind());
        if (node.getSimpleName() != null) {
            updateAttribute(typeNode, "NAME:" + node.getSimpleName(), "NAME:" + oldName(el, className));
        }

        if (el instanceof TypeElement typeEl) {
            updateAllCategories(context, "FQN:" + typeText(typeEl.getQualifiedName()));
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
        if (trees.getSourcePositions().getEndPosition(unit, node) < 0) {
            // A constructor javac added, as the default one or the canonical one of a record: not in the source.
            return null;
        }
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
            methodNode.setJavadoc(trees.getDocComment(getCurrentPath()));
            setLines(methodNode, node, node.getModifiers());
            updateAllCategories(context, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAttribute(methodNode, "NAME:" + node.getName(), "NAME:" + oldName(el, methodName));
            }

            updateCommonFacets(methodNode, methodName, node.getModifiers());
            if (el instanceof ExecutableElement execEl) {
                updateAllCategories(context, "RETURN_TYPE:" + typeText(execEl.getReturnType()));
                methodNode.updateFacet(Facet.RETURN_TYPE, typeText(execEl.getReturnType()));
                for (var param : execEl.getParameters()) {
                    updateAllCategories(context, "PARAM_TYPE:" + typeText(param.asType()));
                    methodNode.updateFacet(Facet.PARAM_TYPES, typeText(param.asType()));
                }
                methodNode.setParamCount(execEl.getParameters().size());
                updateTypeParams(methodNode, execEl.getTypeParameters());
                execEl.getThrownTypes().stream().map(this::typeText).sorted()
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
        if (path != null && path.getParentPath() != null && path.getParentPath().getLeaf() instanceof ClassTree owner) {
            if (ElementInfo.isRecordComponent(owner, node)) {
                // Not a member of its own: the component is a part of the signature of the record.
                ElementInfo record = context.getCurrentNode();
                String name = node.getName().toString();
                updateAttribute(record, "COMPONENT:" + name,
                        "COMPONENT:" + oldName(trees.getElement(path), name));
                scan(node.getModifiers(), context);
                scan(node.getType(), context);
                return null;
            }
            // Field
            ElementInfo parentNode = context.getCurrentNode();
            String varName = node.getName() != null ? node.getName().toString() : "";
            ElementInfo varNode = new ElementInfo(node, varName, ElementInfo.NodeType.FIELD);
            parentNode.addChild(varNode);

            context.setCurrentNode(varNode);
            Element el = trees.getElement(getCurrentPath());
            varNode.setElementId(ElementIds.of(el));
            varNode.setJavadoc(trees.getDocComment(getCurrentPath()));
            setLines(varNode, node, node.getModifiers());
            updateAttribute(varNode, "KIND:" + node.getKind());
            if (node.getName() != null) {
                updateAttribute(varNode, "NAME:" + node.getName(), "NAME:" + oldName(el, varName));
            }

            updateCommonFacets(varNode, varName, node.getModifiers());
            if (el instanceof VariableElement varEl) {
                updateAttribute(varNode, "TYPE:" + typeText(varEl.asType()));
                varNode.updateFacet(Facet.RETURN_TYPE, typeText(varEl.asType()));
            }

            if (el != null && el.getKind() == ElementKind.ENUM_CONSTANT) {
                synthesized.add(node.getType());
                if (node.getInitializer() instanceof NewClassTree newClass) {
                    synthesized.add(newClass.getIdentifier());
                    if (newClass.getClassBody() != null) {
                        synthesized.add(newClass.getClassBody().getExtendsClause());
                    }
                }
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
                if (!hashes(category)) {
                    continue;
                }
                if (category.undoesRenamedReferences() && !isLocalOrParam && name != null) {
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
            String oldName = oldName(trees.getElement(getCurrentPath()), name.toString());
            for (ChangeCategory category : ChangeCategory.values()) {
                if (hashes(category)) {
                    category.onAttribute(category, current,
                            "IDENTIFIER:" + (category.undoesRenamedReferences() ? oldName : name), region());
                }
            }
        }
    }

    @Override
    public Void visitModifiers(ModifiersTree node, HashContext context) {
        updateAllCategories(context, "KIND:" + node.getKind());
        updateAllCategories(context, "MODIFIERS:" + node.getFlags());
        return super.visitModifiers(node, context);
    }

    /**
     * Gives the element the lines of its trees and of its declaration: the first line of code after the
     * annotations, as the annotations are shown on lines of their own.
     */
    private void setLines(ElementInfo info, Tree tree, ModifiersTree modifiers) {
        SourcePositions positions = trees.getSourcePositions();
        long start = positions.getStartPosition(unit, tree);
        if (!modifiers.getAnnotations().isEmpty()) {
            long end = positions.getEndPosition(unit, modifiers.getAnnotations().getLast());
            start = end < 0 ? start : skipSpaceAndComments((int) end);
        }
        info.setSource(sourceText, start < 0 ? 0 : unit.getLineMap().getLineNumber(start));
        // The first line of the javadoc is found by its text after the /** preceding the declaration: javac leaves
        // out the line break after the /**.
        long declaration = positions.getStartPosition(unit, tree);
        int comment = info.getJavadoc().isEmpty() || declaration < 0 ? -1
                : source.lastIndexOf("/**", (int) declaration);
        int first = comment < 0 ? -1 : source.indexOf(info.getJavadoc().getFirst().strip(), comment + 3);
        info.setJavadocLine(first < 0 || first > declaration ? 0 : unit.getLineMap().getLineNumber(first));
    }

    /** The position of the first character from the position on which is neither a space nor in a comment. */
    private int skipSpaceAndComments(int from) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static void updateCommonFacets(ElementInfo info, String name, ModifiersTree modifiers) {
        info.updateFacet(Facet.NAME, name);
        info.updateFacet(Facet.MODIFIERS, String.valueOf(modifiers.getFlags()));
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            info.updateFacet(Facet.ANNOTATIONS, annotation.toString());
        }
    }

    private void updateTypeParams(ElementInfo info, List<? extends TypeParameterElement> typeParams) {
        for (TypeParameterElement tp : typeParams) {
            info.updateFacet(Facet.TYPE_PARAMS, tp + ":" + typeText(tp.getBounds()));
        }
    }

    /** The type as javac prints it, with the old qualified names of the types which were renamed or moved. */
    private String typeText(Object type) {
        return renames.undoTypeNames(String.valueOf(type));
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
     * Updates the digests of all categories. The categories which undo renamed references get the attribute with
     * the old qualified names of types.
     *
     * @param withoutRenames what the categories which {@link ChangeCategory#undoesRenames() undo renames} get
     *                       instead of the attribute, {@code null} for the attribute with the old type names
     */
    private void updateAttribute(ElementInfo node, String attribute, String withoutRenames) {
        for (ChangeCategory category : ChangeCategory.values()) {
            if (!hashes(category)) {
                continue;
            }
            String text = attribute;
            if (category.undoesRenames() && withoutRenames != null) {
                text = withoutRenames;
            } else if (category.undoesRenamedReferences() && !attribute.startsWith("LITERAL:")) {
                text = renames.undoTypeNames(attribute);
            }
            category.onAttribute(category, node, text, region());
        }
    }

    /** Whether the category gets the attributes of the tree being scanned, see {@link #referenceDepth}. */
    private boolean hashes(ChangeCategory category) {
        return referenceDepth == 0 || !category.undoesRenamedReferences();
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
