/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ElementInfo;
import cz.hrebejk.sedi.analysis.ElementPair;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the signature of an {@link ElementPair} as a single line in which every changed part
 * shows the old value struck through followed by the new value, for example
 * {@code public String method( ~~int~~ __float__ f )}. Renamed parameters show only the new name: the rename is cosmetic.
 * Added elements are prefixed with {@code +}, removed elements with {@code -}. The line is colored by a
 * {@link Theme}.
 */
public final class SignatureDiffPrinter {

    private final Theme theme;

    /**
     * Creates a printer.
     *
     * @param theme the colors of the code; {@link Theme#PLAIN} strikes through with {@code ~~old~~}
     */
    public SignatureDiffPrinter(Theme theme) {
        this.theme = theme;
    }

    /**
     * Formats the signature difference of the pair, without the annotations, see {@link #annotations}.
     *
     * @param pair     the pair to print
     * @param typeName simple name of the enclosing type, used to display constructors
     * @return a one line description
     */
    public String format(ElementPair pair, String typeName) {
        return format(pair, typeName, true);
    }

    /**
     * Formats the signature of a type which is shown because something in it changed. It starts with {@code ~} only
     * if the signature itself changed.
     *
     * @param pair     the old and new version of the type
     * @param typeName simple name of the enclosing type
     * @return a one line description
     */
    public String formatType(ElementPair pair, String typeName) {
        return format(pair, typeName, false);
    }

    private String format(ElementPair pair, String typeName, boolean alwaysMarked) {
        if (pair.isAdded()) {
            Parts parts = Parts.of(pair.newElement(), typeName, theme);
            return theme.changed(true, "+ " + theme.code(render(parts, parts)));
        }
        if (pair.isRemoved()) {
            Parts parts = Parts.of(pair.oldElement(), typeName, theme);
            return theme.changed(false, "- " + strike(render(parts, parts)));
        }
        Parts o = Parts.of(pair.oldElement(), typeName, theme);
        Parts n = Parts.of(pair.newElement(), typeName, theme);
        String signature = render(o, n);
        boolean changed = alwaysMarked || !signature.equals(render(n, n));
        return (changed ? theme.modified() : " ") + " " + theme.code(signature);
    }

    /**
     * Formats the annotations of the pair, each on a line of its own: all of them for an added or removed element,
     * for a changed one only the annotations which were added, removed or changed.
     *
     * @param pair the pair to print
     * @return the lines, empty if there is nothing to show
     */
    public List<String> annotations(ElementPair pair) {
        if (pair.isAdded() || pair.isRemoved()) {
            boolean added = pair.isAdded();
            return Parts.of(added ? pair.newElement() : pair.oldElement(), "", theme).annotations().stream().map(Piece::text)
                    .map(a -> theme.changed(added, (added ? "+ " : "- ") + (added ? theme.code(a) : strike(a)))).toList();
        }
        List<Piece> oldAnnotations = Parts.of(pair.oldElement(), "", theme).annotations();
        List<Piece> newAnnotations = Parts.of(pair.newElement(), "", theme).annotations();
        List<String> lines = new ArrayList<>();
        for (Piece o : oldAnnotations) {
            if (newAnnotations.stream().noneMatch(n -> sameAnnotation(o, n))) {
                lines.add(theme.modified() + " " + strike(o.text()));
            }
        }
        for (Piece n : newAnnotations) {
            Piece o = oldAnnotations.stream().filter(a -> sameAnnotation(a, n)).findFirst().orElse(Piece.NONE);
            if (!o.sameAs(n)) {
                lines.add(theme.modified() + " "
                        + (o.text().isEmpty() ? theme.added(n.text()) : slot(o, n)));
            }
        }
        return lines;
    }

    /** Whether two annotations are of the same type: equal names, or equal as a whole, e.g. a renamed type. */
    private static boolean sameAnnotation(Piece a, Piece b) {
        return a.sameAs(b) || annotationName(a.text()).equals(annotationName(b.text()));
    }

    private static String annotationName(String annotation) {
        int paren = annotation.indexOf('(');
        return paren < 0 ? annotation : annotation.substring(0, paren);
    }

    /**
     * Formats the annotations and the signature of an element as it is, without a {@code +} or {@code -}.
     *
     * @param element  the element
     * @param typeName simple name of the enclosing type, used to display constructors
     * @return the lines of the annotations, then the line of the signature
     */
    public List<String> declaration(ElementInfo element, String typeName) {
        Parts parts = Parts.of(element, typeName, theme);
        List<String> lines = new ArrayList<>(parts.annotations().stream().map(Piece::text).map(theme::code).toList());
        lines.add(theme.code(render(parts, parts)));
        return lines;
    }

    /**
     * A part of a signature: the text shown and the key it is compared by. The key of a tree is its hash, in which
     * references to renamed elements are undone, so a part which only refers to a renamed type is unchanged.
     */
    private record Piece(String text, String key) {

        static final Piece NONE = new Piece("", "");

        /** A part compared by its text, like a modifier or a name. */
        static Piece text(String text) {
            return new Piece(text, text);
        }

        /**
         * A part of the signature of the element, compared by its hash if it has one, else by its text. A part with
         * a name javac could not resolve is shown as an error.
         */
        static Piece of(ElementInfo info, Tree tree, Theme theme) {
            if (tree == null) {
                return NONE;
            }
            String sha = info.getSignatureSha(tree);
            String text = tree.toString();
            return new Piece(hasUnresolved(info, tree) ? theme.error(text) : text, sha == null ? text : sha);
        }

        /** Whether the tree is, or contains, a name javac could not resolve. */
        private static boolean hasUnresolved(ElementInfo info, Tree tree) {
            Boolean found = tree.accept(new TreeScanner<Boolean, Void>() {
                @Override
                public Boolean scan(Tree node, Void unused) {
                    return node != null && (info.isUnresolved(node) || Boolean.TRUE.equals(super.scan(node, unused)));
                }

                @Override
                public Boolean reduce(Boolean a, Boolean b) {
                    return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
                }
            }, null);
            return info.isUnresolved(tree) || Boolean.TRUE.equals(found);
        }

        boolean sameAs(Piece other) {
            return key.equals(other.key);
        }
    }

    /** The pieces of one element's signature. */
    private record Parts(ElementInfo.NodeType nodeType, List<Piece> annotations, List<Piece> modifiers,
                         List<Piece> typeParams, Piece type, String name, List<Piece[]> params, List<Piece> throwsList,
                         List<Piece> extendsList, List<Piece> implementsList, List<Piece> permitsList) {

        static Parts of(ElementInfo info, String typeName, Theme theme) {
            Tree tree = info.getOriginalTree();
            if (tree instanceof MethodTree m) {
                String name = m.getName().contentEquals("<init>") ? typeName : m.getName().toString();
                List<Piece[]> params = new ArrayList<>();
                for (VariableTree p : m.getParameters()) {
                    params.add(new Piece[]{Piece.of(info, p.getType(), theme), Piece.text(p.getName().toString())});
                }
                return new Parts(info.getNodeType(), annotations(info, m.getModifiers(), theme), modifiers(m.getModifiers()),
                        pieces(info, m.getTypeParameters(), theme), Piece.of(info, m.getReturnType(), theme), name, params,
                        pieces(info, m.getThrows(), theme), List.of(), List.of(), List.of());
            }
            if (tree instanceof VariableTree v) {
                Piece type = Piece.of(info, v.getType(), theme);
                if (info.getSignatureSha(v.getType()) == null) {
                    // A type javac added, as to an enum constant: compared by the type itself, renames undone.
                    type = new Piece(type.text(), info.getFacetSha(cz.hrebejk.sedi.analysis.Facet.RETURN_TYPE));
                }
                return new Parts(info.getNodeType(), annotations(info, v.getModifiers(), theme), modifiers(v.getModifiers()),
                        List.of(), type, v.getName().toString(), List.of(), List.of(), List.of(), List.of(), List.of());
            }
            if (tree instanceof ClassTree c) {
                // An interface lists the interfaces it extends where a class lists the ones it implements.
                List<Piece> extended = new ArrayList<>();
                List<Piece> implemented = new ArrayList<>();
                if (c.getExtendsClause() != null) {
                    extended.add(Piece.of(info, c.getExtendsClause(), theme));
                }
                (c.getKind() == Tree.Kind.INTERFACE ? extended : implemented)
                        .addAll(pieces(info, c.getImplementsClause(), theme));
                return new Parts(info.getNodeType(), annotations(info, c.getModifiers(), theme), modifiers(c.getModifiers()),
                        pieces(info, c.getTypeParameters(), theme), Piece.text(keyword(c.getKind())),
                        c.getSimpleName().toString(), List.of(), List.of(), extended, implemented,
                        pieces(info, c.getPermitsClause(), theme));
            }
            return new Parts(info.getNodeType(), List.of(), List.of(), List.of(), Piece.NONE, info.getName(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }

        private static List<Piece> annotations(ElementInfo info, ModifiersTree modifiers, Theme theme) {
            return pieces(info, modifiers.getAnnotations(), theme);
        }

        private static List<Piece> modifiers(ModifiersTree modifiers) {
            List<Piece> result = new ArrayList<>();
            for (Modifier m : modifiers.getFlags()) {
                result.add(Piece.text(m.toString()));
            }
            return result;
        }

        private static List<Piece> pieces(ElementInfo info, List<? extends Tree> trees, Theme theme) {
            return trees.stream().map(t -> Piece.of(info, t, theme)).toList();
        }

        private static String keyword(Tree.Kind kind) {
            return switch (kind) {
                case ANNOTATION_TYPE -> "@interface";
                default -> kind.name().toLowerCase();
            };
        }
    }

    private String render(Parts o, Parts n) {
        List<String> out = new ArrayList<>();
        out.add(tokens(o.modifiers(), n.modifiers()));
        switch (n.nodeType()) {
            case METHOD -> {
                out.add(typeParams(o, n));
                out.add(slot(o.type(), n.type()));
                out.add(declared(o.name(), n.name()) + "(" + params(o, n) + ")");
                addClause(out, "throws", o.throwsList(), n.throwsList());
            }
            case FIELD -> {
                out.add(slot(o.type(), n.type()));
                out.add(declared(o.name(), n.name()));
            }
            default -> {
                out.add(slot(o.type(), n.type()));
                out.add(declared(o.name(), n.name()) + typeParams(o, n));
                addClause(out, "extends", o.extendsList(), n.extendsList());
                addClause(out, "implements", o.implementsList(), n.implementsList());
                addClause(out, "permits", o.permitsList(), n.permitsList());
            }
        }
        return join(out, " ");
    }

    /** Adds the clause with its keyword, if the old or the new version has it; the changed items show the change. */
    private void addClause(List<String> out, String keyword, List<Piece> oldItems, List<Piece> newItems) {
        String items = tokens(oldItems, newItems, ", ");
        if (!items.isEmpty()) {
            out.add(keyword + " " + items);
        }
    }

    /** The type parameters in angle brackets, the removed ones struck through and the added ones underlined. */
    private String typeParams(Parts o, Parts n) {
        String params = tokens(o.typeParams(), n.typeParams(), ", ");
        return params.isEmpty() ? "" : "<" + params + ">";
    }

    private String params(Parts o, Parts n) {
        List<String> out = new ArrayList<>();
        Piece[] none = {Piece.NONE, Piece.NONE};
        int count = Math.max(o.params().size(), n.params().size());
        for (int i = 0; i < count; i++) {
            Piece[] op = i < o.params().size() ? o.params().get(i) : none;
            Piece[] np = i < n.params().size() ? n.params().get(i) : none;
            // A renamed parameter is a cosmetic change, not one of the signature: only the new name is shown.
            String name = np[1].text().isEmpty() ? slot(op[1], np[1]) : np[1].text();
            out.add(join(List.of(slot(op[0], np[0]), name), " "));
        }
        return out.isEmpty() ? "" : " " + join(out, ", ") + " ";
    }

    /** Like {@link #slot(String, String)}, the parts compared by their keys. */
    private String slot(Piece oldPiece, Piece newPiece) {
        if (oldPiece.sameAs(newPiece) || oldPiece.text().isEmpty()) {
            return newPiece.text();
        }
        return newPiece.text().isEmpty() ? strike(oldPiece.text()) : theme.replace(oldPiece.text(), newPiece.text());
    }

    /**
     * Formats a changed value: the old value struck through followed by the new one; unchanged values
     * are printed once.
     *
     * @param oldValue the old value, may be empty
     * @param newValue the new value, may be empty
     * @return the formatted text
     */
    String slot(String oldValue, String newValue) {
        if (oldValue.equals(newValue) || oldValue.isEmpty()) {
            return newValue;
        }
        return newValue.isEmpty() ? strike(oldValue) : theme.replace(oldValue, newValue);
    }

    /** Like {@link #slot}, the new name shown as a declaration. */
    private String declared(String oldName, String newName) {
        String name = theme.style(Theme.Role.DECLARATION, newName);
        return oldName.equals(newName) || oldName.isEmpty() ? name : theme.replace(oldName, name);
    }

    /** Removed tokens struck through, followed by all tokens of the new version, the added ones underlined. */
    private String tokens(List<Piece> oldTokens, List<Piece> newTokens) {
        return tokens(oldTokens, newTokens, " ");
    }

    private String tokens(List<Piece> oldTokens, List<Piece> newTokens, String separator) {
        List<String> removed = new ArrayList<>();
        for (Piece t : oldTokens) {
            if (newTokens.stream().noneMatch(t::sameAs)) {
                removed.add(strike(t.text()));
            }
        }
        List<String> current = new ArrayList<>();
        for (Piece t : newTokens) {
            current.add(oldTokens.stream().anyMatch(t::sameAs) ? t.text() : theme.added(t.text()));
        }
        return join(List.of(join(removed, separator), join(current, separator)), " ");
    }

    /** The text struck through. */
    String strike(String text) {
        return theme.strike(text);
    }

    private static String join(List<String> parts, String separator) {
        return parts.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining(separator));
    }
}
