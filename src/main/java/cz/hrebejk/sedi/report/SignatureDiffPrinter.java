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

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the signature of an {@link ElementPair} as a single line in which every changed part
 * shows the old value struck through followed by the new value, for example
 * {@code public String method( ~~int~~ float ~~i~~ f )}.
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
            Parts parts = Parts.of(pair.newElement(), typeName);
            return theme.changed(true, "+ " + theme.code(render(parts, parts)));
        }
        if (pair.isRemoved()) {
            Parts parts = Parts.of(pair.oldElement(), typeName);
            return theme.changed(false, "- " + strike(render(parts, parts)));
        }
        Parts o = Parts.of(pair.oldElement(), typeName);
        Parts n = Parts.of(pair.newElement(), typeName);
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
            return Parts.of(added ? pair.newElement() : pair.oldElement(), "").annotations().stream()
                    .map(a -> theme.changed(added, (added ? "+ " : "- ") + (added ? theme.code(a) : strike(a)))).toList();
        }
        List<String> oldAnnotations = Parts.of(pair.oldElement(), "").annotations();
        List<String> newAnnotations = Parts.of(pair.newElement(), "").annotations();
        List<String> lines = new ArrayList<>();
        for (String o : oldAnnotations) {
            if (newAnnotations.stream().noneMatch(n -> annotationName(n).equals(annotationName(o)))) {
                lines.add(theme.modified() + " " + strike(o));
            }
        }
        for (String n : newAnnotations) {
            String o = oldAnnotations.stream().filter(a -> annotationName(a).equals(annotationName(n))).findFirst().orElse("");
            if (!o.equals(n)) {
                lines.add(theme.modified() + " " + (o.isEmpty() ? theme.style(Theme.Role.REPLACEMENT, n) : slot(o, n)));
            }
        }
        return lines;
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
        Parts parts = Parts.of(element, typeName);
        List<String> lines = new ArrayList<>(parts.annotations().stream().map(theme::code).toList());
        lines.add(theme.code(render(parts, parts)));
        return lines;
    }

    /** Textual pieces of one element's signature. */
    private record Parts(ElementInfo.NodeType nodeType, List<String> annotations, List<String> modifiers, List<String> typeParams, String type,
                         String name, List<String[]> params, List<String> throwsList, List<String> extendsList,
                         List<String> implementsList, List<String> permitsList) {

        static Parts of(ElementInfo info, String typeName) {
            Tree tree = info.getOriginalTree();
            if (tree instanceof MethodTree m) {
                String name = m.getName().contentEquals("<init>") ? typeName : m.getName().toString();
                List<String[]> params = new ArrayList<>();
                for (VariableTree p : m.getParameters()) {
                    params.add(new String[]{String.valueOf(p.getType()), p.getName().toString()});
                }
                return new Parts(info.getNodeType(), annotations(m.getModifiers()), modifiers(m.getModifiers()), typeParams(m.getTypeParameters()),
                        m.getReturnType() == null ? "" : m.getReturnType().toString(), name, params,
                        m.getThrows().stream().map(Object::toString).toList(), List.of(), List.of(), List.of());
            }
            if (tree instanceof VariableTree v) {
                return new Parts(info.getNodeType(), annotations(v.getModifiers()), modifiers(v.getModifiers()), List.of(), String.valueOf(v.getType()),
                        v.getName().toString(), List.of(), List.of(), List.of(), List.of(), List.of());
            }
            if (tree instanceof ClassTree c) {
                // An interface lists the interfaces it extends where a class lists the ones it implements.
                List<String> extended = new ArrayList<>();
                List<String> implemented = new ArrayList<>();
                if (c.getExtendsClause() != null) {
                    extended.add(c.getExtendsClause().toString());
                }
                (c.getKind() == Tree.Kind.INTERFACE ? extended : implemented)
                        .addAll(c.getImplementsClause().stream().map(Object::toString).toList());
                return new Parts(info.getNodeType(), annotations(c.getModifiers()), modifiers(c.getModifiers()), typeParams(c.getTypeParameters()),
                        keyword(c.getKind()), c.getSimpleName().toString(), List.of(), List.of(), extended, implemented,
                        c.getPermitsClause().stream().map(Object::toString).toList());
            }
            return new Parts(info.getNodeType(), List.of(), List.of(), List.of(), "", info.getName(), List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }

        private static List<String> annotations(ModifiersTree modifiers) {
            return modifiers.getAnnotations().stream().map(AnnotationTree::toString).toList();
        }

        private static List<String> modifiers(ModifiersTree modifiers) {
            List<String> result = new ArrayList<>();
            for (Modifier m : modifiers.getFlags()) {
                result.add(m.toString());
            }
            return result;
        }

        private static List<String> typeParams(List<? extends Tree> params) {
            return params.stream().map(Object::toString).toList();
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
    private void addClause(List<String> out, String keyword, List<String> oldItems, List<String> newItems) {
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
        int count = Math.max(o.params().size(), n.params().size());
        for (int i = 0; i < count; i++) {
            String[] op = i < o.params().size() ? o.params().get(i) : new String[]{"", ""};
            String[] np = i < n.params().size() ? n.params().get(i) : new String[]{"", ""};
            out.add(join(List.of(slot(op[0], np[0]), slot(op[1], np[1])), " "));
        }
        return out.isEmpty() ? "" : " " + join(out, ", ") + " ";
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
    private String tokens(List<String> oldTokens, List<String> newTokens) {
        return tokens(oldTokens, newTokens, " ");
    }

    private String tokens(List<String> oldTokens, List<String> newTokens, String separator) {
        List<String> removed = new ArrayList<>();
        for (String t : oldTokens) {
            if (!newTokens.contains(t)) {
                removed.add(strike(t));
            }
        }
        List<String> current = new ArrayList<>();
        for (String t : newTokens) {
            current.add(oldTokens.contains(t) ? t : theme.style(Theme.Role.REPLACEMENT, t));
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
