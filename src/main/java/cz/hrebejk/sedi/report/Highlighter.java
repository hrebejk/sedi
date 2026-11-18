/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Highlights the syntax of Java code: puts its tokens into spans of the {@link Css} classes of their roles, like
 * {@link Css#KEYWORD} or {@link Css#STRING}. The roles are judged by the tokens themselves and what follows them,
 * not by the AST: the code shown may be a part of a statement, or old and new code mixed.
 */
final class Highlighter {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var",
            "record", "yield", "sealed", "permits", "non-sealed", "when");

    private Highlighter() {
    }

    /** Plain Java code with its tokens highlighted. */
    static Fragment code(String code) {
        return code(Fragment.of(code));
    }

    /**
     * Java code with its tokens highlighted. The code struck through, marked as an error or as a comment, and the
     * code already highlighted, is left as it is; the code in other spans, like the added code, is highlighted
     * inside them.
     */
    static Fragment code(Fragment code) {
        return Fragment.of(code(code.nodes(), ""), code.changed());
    }

    /**
     * Java code of several lines, every line highlighted as by {@link #code(String)}. A comment going on over
     * several lines is shown as a comment on each of them.
     *
     * @param code the code, its lines separated by {@code \n}
     * @return the lines
     */
    static List<Fragment> codeLines(String code) {
        List<Fragment> lines = new ArrayList<>();
        List<HTML> line = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            int start = i;
            if (c == '\n') {
                lines.add(endLine(line, text));
                i++;
            } else if (code.startsWith("/*", i)) {
                int close = code.indexOf("*/", i + 2);
                i = close < 0 ? code.length() : close + 2;
                String[] parts = code.substring(start, i).split("\n", -1);
                for (int p = 0; p < parts.length; p++) {
                    if (p > 0) {
                        lines.add(endLine(line, text));
                    }
                    if (!parts[p].isEmpty()) {
                        flush(line, text);
                        line.add(Css.COMMENT.span(parts[p]));
                    }
                }
            } else if (code.startsWith("//", i)) {
                int eol = code.indexOf('\n', i);
                i = eol < 0 ? code.length() : eol;
                text.append(code, start, i);
            } else if (c == '"' || c == '\'') {
                // A literal is copied whole, so a comment start in it is not taken for a comment.
                i++;
                while (i < code.length() && code.charAt(i) != c && code.charAt(i) != '\n') {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, code.length());
                text.append(code, start, i);
            } else {
                text.append(c);
                i++;
            }
        }
        lines.add(endLine(line, text));
        return lines;
    }

    /** The highlighted line, the line and the text cleared for the next one. */
    private static Fragment endLine(List<HTML> line, StringBuilder text) {
        flush(line, text);
        Fragment result = code(Fragment.of(line, false));
        line.clear();
        return result;
    }

    private static void flush(List<HTML> line, StringBuilder text) {
        if (!text.isEmpty()) {
            line.add(HTML.Text(text.toString()));
            text.setLength(0);
        }
    }

    /**
     * The nodes highlighted.
     *
     * @param after the text following the nodes, to tell the role of the last word
     */
    private static List<HTML> code(List<HTML> nodes, String after) {
        List<HTML> out = new ArrayList<>();
        for (int n = 0; n < nodes.size(); n++) {
            HTML node = nodes.get(n);
            String rest = text(nodes.subList(n + 1, nodes.size())) + after;
            switch (node) {
                case HTML.Text t -> out.addAll(tokens(t.text, rest));
                case HTML.Tag t when isLeftAsItIs(t) -> out.add(t);
                case HTML.Tag t -> out.add(HTML.Tag(t.tagName).attributes(t.attrs).children(code(t.children, rest)));
            }
        }
        return out;
    }

    private static boolean isLeftAsItIs(HTML.Tag tag) {
        if (Css.STRIKE.on(tag) || Css.ERROR.on(tag) || Css.COMMENT.on(tag)) {
            return true;
        }
        for (Css css : Css.values()) {
            if (css.isHighlight() && css.on(tag)) {
                return true;
            }
        }
        return false;
    }

    private static String text(List<HTML> nodes) {
        StringBuilder sb = new StringBuilder();
        nodes.forEach(n -> sb.append(n.getInnerText()));
        return sb.toString();
    }

    /**
     * The tokens of plain code, each in the span of its role, the text between them as it is.
     *
     * @param after the text following the code, to tell the role of the last word
     */
    private static List<HTML> tokens(String code, String after) {
        List<HTML> out = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int length = code.length();
        int i = 0;
        while (i < length) {
            char c = code.charAt(i);
            int start = i;
            Css role = null;
            if (c == '"' || c == '\'') {
                i++;
                while (i < length && code.charAt(i) != c) {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, length);
                role = Css.STRING;
            } else if (c == '/' && i + 1 < length && (code.charAt(i + 1) == '/' || code.charAt(i + 1) == '*')) {
                int end = code.charAt(i + 1) == '/' ? code.indexOf('\n', i) : code.indexOf("*/", i + 2);
                i = end < 0 ? length : code.charAt(i + 1) == '/' ? end : end + 2;
                role = Css.COMMENT;
            } else if (Character.isDigit(c)) {
                while (i < length && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '_'
                        || code.charAt(i) == '.' && i + 1 < length && Character.isDigit(code.charAt(i + 1)))) {
                    i++;
                }
                role = Css.NUMBER;
            } else if (c == '@' && i + 1 < length && Character.isJavaIdentifierStart(code.charAt(i + 1))) {
                i++;
                while (i < length && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
                    i++;
                }
                role = Css.ANNOTATION;
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < length && Character.isJavaIdentifierPart(code.charAt(i))) {
                    i++;
                }
                role = role(code.substring(start, i), code.substring(i) + after).orElse(null);
            } else if (c == '…') {
                i++;
                role = Css.NOTE;
            } else {
                i++;
            }
            if (role == null) {
                plain.append(code, start, i);
            } else {
                if (!plain.isEmpty()) {
                    out.add(HTML.Text(plain.toString()));
                    plain.setLength(0);
                }
                out.add(role.span(code.substring(start, i)));
            }
        }
        if (!plain.isEmpty()) {
            out.add(HTML.Text(plain.toString()));
        }
        return out;
    }

    /** The role of a word, judged by the word and by the text following it. */
    private static Optional<Css> role(String word, String following) {
        if (KEYWORDS.contains(word)) {
            return Optional.of(Css.KEYWORD);
        }
        if (following.stripLeading().startsWith("(")) {
            return Optional.of(Css.METHOD);
        }
        if (word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT)) && !word.equals("_")
                && word.chars().anyMatch(Character::isLetter)) {
            return Optional.of(Css.CONSTANT);
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return Optional.of(Css.TYPE);
        }
        return Optional.empty();
    }
}
