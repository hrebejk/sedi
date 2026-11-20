/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * The syntax roles of the text of a compilation unit, like a keyword, a literal or the name of a method, for its
 * highlighting. The roles of names are told by the elements javac resolved them to, the literals by their trees.
 * Only the reserved keywords and the comments, which have no trees, are found in the text; the contextual keywords,
 * like {@code var} or {@code record}, are keywords only where the trees make them ones.
 * <p>
 * The names of declared types, methods and fields are marked as {@linkplain #isDeclaration declarations}.
 */
public final class SyntaxRoles {

    /** The role of a piece of code. */
    public enum Role {
        /** Reserved and contextual keywords, including modifiers and primitive types. */
        KEYWORD,
        /** Names of types and type parameters. */
        TYPE,
        /** Names of methods and constructors. */
        METHOD,
        /** Names of fields which are not constants, and record components. */
        FIELD,
        /** Names of static final fields and enum constants. */
        CONSTANT,
        /** Literals: numbers, strings, characters, text blocks, booleans and {@code null}. */
        LITERAL,
        /** Annotations: the {@code @} and the name of the type. */
        ANNOTATION,
        /** Comments. */
        COMMENT
    }

    /**
     * A run of text of the same role.
     *
     * @param text        the text
     * @param role        the role, {@code null} for code without one, like spaces, operators or local variables
     * @param declaration whether it is the name of a declared type, method or field
     */
    public record Run(String text, Role role, boolean declaration) {
    }

    /** The reserved keywords, the literals {@code true}, {@code false} and {@code null} are not keywords. */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while");

    private final String text;
    private final Role[] roles;
    private final BitSet declarations = new BitSet();

    private SyntaxRoles(String text) {
        this.text = text;
        this.roles = new Role[text.length()];
    }

    /**
     * The roles of the code of a tree and of the trees in it.
     *
     * @param info the compilation unit, attributed
     * @param text the source text of the unit
     * @param root the tree, the compilation unit for all of the text
     * @return the roles; the text out of the tree has none
     */
    public static SyntaxRoles of(CompilationUnitInfo info, String text, Tree root) {
        return of(info.trees(), info.compilationUnit(), text, root);
    }

    /**
     * The roles of the code of a tree and of the trees in it.
     *
     * @param trees the trees of the attributed unit
     * @param unit  the compilation unit
     * @param text  the source text of the unit
     * @param root  the tree, the compilation unit for all of the text
     * @return the roles; the text out of the tree has none
     */
    public static SyntaxRoles of(Trees trees, CompilationUnitTree unit, String text, Tree root) {
        SyntaxRoles result = new SyntaxRoles(text);
        SourcePositions positions = trees.getSourcePositions();
        boolean whole = root == unit;
        int from = whole ? 0 : (int) positions.getStartPosition(unit, root);
        int to = whole ? text.length() : (int) positions.getEndPosition(unit, root);
        if (from < 0 || to <= from) {
            return result;
        }
        result.lexical(from, Math.min(to, text.length()));
        TreePath path = whole ? new TreePath(unit) : trees.getPath(unit, root);
        if (path != null) {
            new Scanner(result, trees, unit, positions).scan(path, null);
        }
        return result;
    }

    /**
     * The roles of the code of a tree, its source read from the unit.
     *
     * @see #of(CompilationUnitInfo, String, Tree)
     */
    public static SyntaxRoles of(CompilationUnitInfo info, Tree root) {
        try {
            return of(info, info.compilationUnit().getSourceFile().getCharContent(true).toString(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The role of the character at the position, {@code null} if it has none. */
    public Role role(int position) {
        return position >= 0 && position < roles.length ? roles[position] : null;
    }

    /** Whether the character at the position is in the name of a declared type, method or field. */
    public boolean isDeclaration(int position) {
        return declarations.get(position);
    }

    /**
     * The text from one position to another in runs of the same role.
     *
     * @param from the start
     * @param to   the end, not included
     * @return the runs
     */
    public List<Run> runs(int from, int to) {
        Runs runs = new Runs();
        for (int i = Math.max(from, 0); i < Math.min(to, text.length()); i++) {
            runs.add(text.charAt(i), role(i), isDeclaration(i));
        }
        return runs.build();
    }

    /** Collects characters into runs of the same role. */
    static final class Runs {

        private final List<Run> runs = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Role role;
        private boolean declaration;

        void add(char c, Role role, boolean declaration) {
            if (!text.isEmpty() && (role != this.role || declaration != this.declaration)) {
                flush();
            }
            this.role = role;
            this.declaration = declaration;
            text.append(c);
        }

        private void flush() {
            if (!text.isEmpty()) {
                runs.add(new Run(text.toString(), role, declaration));
                text.setLength(0);
            }
        }

        List<Run> build() {
            flush();
            return List.copyOf(runs);
        }
    }

    /** Marks the comments and the reserved keywords; string literals are skipped, they are marked as literals. */
    private void lexical(int from, int to) {
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            char next = i + 1 < to ? text.charAt(i + 1) : 0;
            int start = i;
            if (c == '/' && next == '/') {
                int eol = text.indexOf('\n', i);
                i = eol < 0 || eol > to ? to : eol;
                mark(start, i, Role.COMMENT);
            } else if (c == '/' && next == '*') {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 || close + 2 > to ? to : close + 2;
                mark(start, i, Role.COMMENT);
            } else if (text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                i = close < 0 || close + 3 > to ? to : close + 3;
                mark(start, i, Role.LITERAL);
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < to && text.charAt(i) != c && text.charAt(i) != '\n') {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, to);
                mark(start, i, Role.LITERAL);
            } else if (Character.isJavaIdentifierPart(c)) {
                // Also numbers, so a letter in them, like in 0xFF, is not taken for a word.
                while (i < to && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                if (KEYWORDS.contains(text.substring(start, i))) {
                    mark(start, i, Role.KEYWORD);
                }
            } else {
                i++;
            }
        }
    }

    private void mark(int from, int to, Role role) {
        for (int i = Math.max(from, 0); i < Math.min(to, roles.length); i++) {
            roles[i] = role;
        }
    }

    /**
     * The position of the first occurrence of the word as a whole word, not in a comment or a literal, {@code -1} if
     * there is none.
     */
    private int find(String word, int from, int to) {
        if (from < 0 || word.isEmpty()) {
            return -1;
        }
        for (int i = text.indexOf(word, from); i >= 0 && i + word.length() <= to; i = text.indexOf(word, i + 1)) {
            boolean before = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
            boolean after = i + word.length() >= text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(i + word.length()));
            if (before && after && roles[i] != Role.COMMENT && roles[i] != Role.LITERAL) {
                return i;
            }
        }
        return -1;
    }

    /** Marks the first occurrence of a contextual keyword, see {@link #find}. */
    private void keyword(String word, int from, int to) {
        int at = find(word, from, to);
        if (at >= 0) {
            mark(at, at + word.length(), Role.KEYWORD);
        }
    }

    /** Marks the name of a declaration, its first occurrence from a position. */
    private void declaration(String name, int from, int to, Role role) {
        int at = find(name, from, to);
        if (at >= 0) {
            mark(at, at + name.length(), role);
            declarations.set(at, at + name.length());
        }
    }

    /** The role of a name of the element. */
    private static Role role(Element element) {
        if (element == null || element.asType() != null && element.asType().getKind() == TypeKind.ERROR) {
            return null;
        }
        return switch (element.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, TYPE_PARAMETER -> Role.TYPE;
            case METHOD, CONSTRUCTOR -> Role.METHOD;
            case ENUM_CONSTANT -> Role.CONSTANT;
            case FIELD -> element.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))
                    ? Role.CONSTANT : Role.FIELD;
            case RECORD_COMPONENT -> Role.FIELD;
            default -> null;
        };
    }

    /** Marks the roles told by the trees. */
    private static final class Scanner extends TreePathScanner<Void, Void> {

        private final SyntaxRoles roles;
        private final Trees trees;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;

        Scanner(SyntaxRoles roles, Trees trees, CompilationUnitTree unit, SourcePositions positions) {
            this.roles = roles;
            this.trees = trees;
            this.unit = unit;
            this.positions = positions;
        }

        private int start(Tree tree) {
            return tree == null ? -1 : (int) positions.getStartPosition(unit, tree);
        }

        private int end(Tree tree) {
            return tree == null ? -1 : (int) positions.getEndPosition(unit, tree);
        }

        /** Marks a name of an element, if it is in the source as it is, not a tree javac added. */
        private void name(CharSequence name, int from, int to) {
            if (from < 0 || to > roles.text.length() || to - from != name.length()
                    || !roles.text.substring(from, to).contentEquals(name)) {
                return;
            }
            Role role = role(trees.getElement(getCurrentPath()));
            if (role != null) {
                roles.mark(from, to, role);
            }
        }

        @Override
        public Void visitLiteral(LiteralTree node, Void unused) {
            roles.mark(start(node), end(node), Role.LITERAL);
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (!node.getName().contentEquals("this") && !node.getName().contentEquals("super")) {
                name(node.getName(), start(node), end(node));
            }
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            super.visitMemberSelect(node, unused);
            String name = node.getIdentifier().toString();
            if (!KEYWORDS.contains(name)) {
                name(name, end(node) - name.length(), end(node));
            }
            return null;
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            super.visitMemberReference(node, unused);
            String name = node.getName().toString();
            if (!name.equals("<init>")) {
                name(name, end(node) - name.length(), end(node));
            }
            return null;
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void unused) {
            roles.mark(start(node), end(node.getAnnotationType()), Role.ANNOTATION);
            // The names of the elements set by the arguments are methods of the annotation type.
            scan(node.getArguments(), unused);
            return null;
        }

        @Override
        public Void visitModifiers(ModifiersTree node, Void unused) {
            super.visitModifiers(node, unused);
            if (node.getFlags().contains(Modifier.SEALED)) {
                roles.keyword("sealed", start(node), end(node));
            }
            if (node.getFlags().contains(Modifier.NON_SEALED)) {
                int at = roles.text.indexOf("non-sealed", Math.max(start(node), 0));
                if (at >= 0 && at < end(node)) {
                    roles.mark(at, at + "non-sealed".length(), Role.KEYWORD);
                }
            }
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            super.visitClass(node, unused);
            String name = node.getSimpleName().toString();
            int from = Math.max(start(node), end(node.getModifiers()));
            if (node.getKind() == Tree.Kind.RECORD) {
                roles.keyword("record", from, end(node));
            }
            if (!name.isEmpty()) {
                roles.declaration(name, from, end(node), Role.TYPE);
            }
            if (!node.getPermitsClause().isEmpty()) {
                roles.keyword("permits", from, start(node.getPermitsClause().getFirst()));
            }
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            super.visitMethod(node, unused);
            if (start(node) < 0) {
                return null;
            }
            String name = node.getName().toString();
            if (name.equals("<init>")) {
                Tree owner = getCurrentPath().getParentPath().getLeaf();
                name = owner instanceof ClassTree c ? c.getSimpleName().toString() : "";
            }
            int from = Math.max(start(node), Math.max(end(node.getModifiers()), end(node.getReturnType())));
            for (TypeParameterTree parameter : node.getTypeParameters()) {
                from = Math.max(from, end(parameter));
            }
            roles.declaration(name, from, end(node), Role.METHOD);
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            super.visitVariable(node, unused);
            int start = start(node);
            if (start < 0) {
                return null;
            }
            Element element = trees.getElement(getCurrentPath());
            boolean constant = element != null && element.getKind() == ElementKind.ENUM_CONSTANT;
            int afterModifiers = Math.max(start, end(node.getModifiers()));
            Tree type = node.getType();
            // The type javac infers for var has no end in the source, an implicit type of a lambda parameter no place.
            boolean var = type == null || start(type) < 0 || end(type) <= start(type);
            int from = constant ? start : Math.max(afterModifiers, var ? -1 : end(type));
            int to = constant || node.getInitializer() == null || start(node.getInitializer()) < 0
                    ? end(node) : start(node.getInitializer());
            String name = node.getName().toString();
            int at = roles.find(name, from, to);
            if (at < 0) {
                return null;
            }
            if (var && !constant) {
                roles.keyword("var", afterModifiers, at);
            }
            Role role = role(element);
            if (role != null) {
                roles.mark(at, at + name.length(), role);
                roles.declarations.set(at, at + name.length());
            }
            return null;
        }

        @Override
        public Void visitTypeParameter(TypeParameterTree node, Void unused) {
            super.visitTypeParameter(node, unused);
            int at = roles.find(node.getName().toString(), start(node), end(node));
            if (at >= 0) {
                roles.mark(at, at + node.getName().length(), Role.TYPE);
            }
            return null;
        }

        @Override
        public Void visitYield(YieldTree node, Void unused) {
            roles.keyword("yield", start(node), start(node) + "yield".length());
            return super.visitYield(node, unused);
        }

        @Override
        public Void visitCase(CaseTree node, Void unused) {
            if (node.getGuard() != null) {
                roles.keyword("when", start(node), start(node.getGuard()));
            }
            return super.visitCase(node, unused);
        }
    }
}
