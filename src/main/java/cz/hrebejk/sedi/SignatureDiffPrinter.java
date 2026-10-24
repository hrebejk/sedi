/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

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
 * Added elements are prefixed with {@code +}, removed elements with {@code -}.
 */
public final class SignatureDiffPrinter {

    private final boolean ansi;

    /**
     * Creates a printer.
     *
     * @param ansi {@code true} to strike through with ANSI escape codes, {@code false} to use {@code ~~old~~}
     */
    public SignatureDiffPrinter(boolean ansi) {
        this.ansi = ansi;
    }

    /**
     * Formats the signature difference of the pair.
     *
     * @param pair     the pair to print
     * @param typeName simple name of the enclosing type, used to display constructors
     * @return a one line description
     */
    public String format(ElementPair pair, String typeName) {
        if (pair.isAdded()) {
            Parts parts = Parts.of(pair.newElement(), typeName);
            return "+ " + render(parts, parts);
        }
        if (pair.isRemoved()) {
            Parts parts = Parts.of(pair.oldElement(), typeName);
            return "- " + render(parts, parts);
        }
        return "  " + render(Parts.of(pair.oldElement(), typeName), Parts.of(pair.newElement(), typeName));
    }

    /** Textual pieces of one element's signature. */
    private record Parts(ElementInfo.NodeType nodeType, List<String> modifiers, String typeParams, String type,
                         String name, List<String[]> params, List<String> throwsList, boolean hasParams) {

        static Parts of(ElementInfo info, String typeName) {
            Tree tree = info.getOriginalTree();
            if (tree instanceof MethodTree m) {
                String name = m.getName().contentEquals("<init>") ? typeName : m.getName().toString();
                List<String[]> params = new ArrayList<>();
                for (VariableTree p : m.getParameters()) {
                    params.add(new String[]{String.valueOf(p.getType()), p.getName().toString()});
                }
                return new Parts(info.getNodeType(), modifiers(m.getModifiers()), typeParams(m.getTypeParameters()),
                        m.getReturnType() == null ? "" : m.getReturnType().toString(), name, params,
                        m.getThrows().stream().map(Object::toString).toList(), true);
            }
            if (tree instanceof VariableTree v) {
                return new Parts(info.getNodeType(), modifiers(v.getModifiers()), "", String.valueOf(v.getType()),
                        v.getName().toString(), List.of(), List.of(), false);
            }
            if (tree instanceof ClassTree c) {
                return new Parts(info.getNodeType(), modifiers(c.getModifiers()), typeParams(c.getTypeParameters()),
                        keyword(c.getKind()), c.getSimpleName().toString(), List.of(), List.of(), false);
            }
            return new Parts(info.getNodeType(), List.of(), "", "", info.getName(), List.of(), List.of(), false);
        }

        private static List<String> modifiers(ModifiersTree modifiers) {
            List<String> result = new ArrayList<>();
            for (AnnotationTree a : modifiers.getAnnotations()) {
                result.add(a.toString());
            }
            for (Modifier m : modifiers.getFlags()) {
                result.add(m.toString());
            }
            return result;
        }

        private static String typeParams(List<? extends Tree> params) {
            return params.isEmpty() ? ""
                    : params.stream().map(Object::toString).collect(Collectors.joining(", ", "<", ">"));
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
                out.add(slot(o.typeParams(), n.typeParams()));
                out.add(slot(o.type(), n.type()));
                out.add(slot(o.name(), n.name()) + "(" + params(o, n) + ")");
                String throwsPart = tokens(o.throwsList(), n.throwsList());
                if (!throwsPart.isEmpty()) {
                    out.add("throws " + throwsPart);
                }
            }
            case FIELD -> {
                out.add(slot(o.type(), n.type()));
                out.add(slot(o.name(), n.name()));
            }
            default -> {
                out.add(slot(o.type(), n.type()));
                out.add(slot(o.name(), n.name()) + n.typeParams());
            }
        }
        return join(out, " ");
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

    /** Old value struck through followed by the new one; unchanged values are printed once. */
    private String slot(String oldValue, String newValue) {
        if (oldValue.equals(newValue) || oldValue.isEmpty()) {
            return newValue;
        }
        return newValue.isEmpty() ? strike(oldValue) : strike(oldValue) + " " + newValue;
    }

    /** Removed tokens struck through, followed by all tokens of the new version. */
    private String tokens(List<String> oldTokens, List<String> newTokens) {
        List<String> out = new ArrayList<>();
        for (String t : oldTokens) {
            if (!newTokens.contains(t)) {
                out.add(strike(t));
            }
        }
        out.addAll(newTokens);
        return join(out, " ");
    }

    private String strike(String text) {
        return ansi ? "\u001b[9m" + text + "\u001b[29m" : "~~" + text + "~~";
    }

    private static String join(List<String> parts, String separator) {
        return parts.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining(separator));
    }
}
