/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The colors and text decorations of the report, rendered with ANSI escape codes in true color. Every {@link Role}
 * of the text has a style: a foreground and a background color, bold, underlined and struck through.
 * <p>
 * A theme can be read from a properties file whose keys are the names of the roles in lower case and whose values
 * are space separated attributes: {@code #rrggbb} or {@code fg:#rrggbb} for the text color, {@code bg:#rrggbb} for
 * the background, {@code bold}, {@code underline} and {@code strike}, or {@code none} for no style, e.g.
 * <pre>
 * keyword = #82aaff bold
 * added   = bg:#1e3a2a
 * </pre>
 * Roles left out keep their default style.
 */
public final class Theme {

    /** What a piece of text is. */
    enum Role {
        /** Java keywords, including modifiers, primitive types and literals like {@code null}. */
        KEYWORD,
        /** Names starting with a capital letter. */
        TYPE,
        /** Names followed by a parenthesis. */
        METHOD,
        /** String and character literals. */
        STRING,
        /** Number literals. */
        NUMBER,
        /** Names in capitals, like {@code MAX_VALUE}. */
        CONSTANT,
        /** Annotations. */
        ANNOTATION,
        /** Comments. */
        COMMENT,
        /** Old text replaced by new one. */
        STRIKE,
        /** New text following the struck through text it replaced. */
        REPLACEMENT,
        /** Lines of added code. */
        ADDED,
        /** Lines of removed code. */
        REMOVED,
        /** The {@code +} starting a line of added code. */
        ADDED_MARK,
        /** The {@code -} starting a line of removed code. */
        REMOVED_MARK,
        /** The name of a declared type, method or field. */
        DECLARATION,
        /** Section headings. */
        HEADING,
        /** The {@code #} starting a section heading. */
        HEADING_MARK,
        /** The {@code ~} starting the title of a changed file. */
        CHANGED_MARK,
        /** The name of a changed file's class. */
        TITLE,
        /** The package in a file heading. */
        PACKAGE,
        /** The tag of the level of a change. */
        TAG,
        /** Remarks of the report, like collapsed or left out code. */
        NOTE,
        /** The remark of a section without changes. */
        NO_CHANGES
    }

    /** A theme without colors; struck through text is shown as {@code ~~text~~}, added text as {@code __text__}. */
    public static final Theme PLAIN = new Theme(Map.of(), false);

    private static final String ESC = "\u001b[";

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var",
            "record", "yield", "sealed", "permits", "non-sealed", "when");

    /** The default pastel styles, meant for a dark terminal. */
    private static final Map<Role, String> DEFAULTS = Map.ofEntries(
            Map.entry(Role.KEYWORD, "#82aaff"),
            Map.entry(Role.TYPE, "#7fd1b9"),
            Map.entry(Role.METHOD, "#f2e2a0"),
            Map.entry(Role.STRING, "#b8e0a0"),
            Map.entry(Role.NUMBER, "#f7c59f"),
            Map.entry(Role.CONSTANT, "#f7c59f"),
            Map.entry(Role.ANNOTATION, "#a6e3e9"),
            Map.entry(Role.COMMENT, "#7f8796"),
            // Removed text on red and added text on green, enough to stand out of the dark background.
            Map.entry(Role.STRIKE, "#d8aeb3 bg:#4f242a strike"),
            Map.entry(Role.REPLACEMENT, "bg:#1c4429 underline"),
            Map.entry(Role.ADDED, "none"),
            Map.entry(Role.REMOVED, "none"),
            Map.entry(Role.ADDED_MARK, "bold #5fd787"),
            Map.entry(Role.REMOVED_MARK, "bold #ff6b78"),
            Map.entry(Role.CHANGED_MARK, "bold #e5c07b"),
            Map.entry(Role.DECLARATION, "bold"),
            Map.entry(Role.HEADING, "bold underline"),
            Map.entry(Role.HEADING_MARK, "#9cc4ff"),
            Map.entry(Role.TITLE, "#e4ecf7 bold"),
            Map.entry(Role.PACKAGE, "#8a93a5"),
            Map.entry(Role.TAG, "#7f8796"),
            Map.entry(Role.NOTE, "#8a93a5"),
            Map.entry(Role.NO_CHANGES, "bold #ff9e3d"));

    /**
     * Text attributes; {@code null} colors are left as they are.
     */
    record Style(Integer fg, Integer bg, boolean bold, boolean underline, boolean strike) {

        /** Reads attributes as described in {@link Theme}. */
        static Style parse(String spec) {
            Integer fg = null;
            Integer bg = null;
            boolean bold = false;
            boolean underline = false;
            boolean strike = false;
            for (String attribute : spec.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                switch (attribute) {
                    case "", "none" -> { }
                    case "bold" -> bold = true;
                    case "underline" -> underline = true;
                    case "strike" -> strike = true;
                    default -> {
                        if (attribute.startsWith("bg:")) {
                            bg = color(attribute.substring(3));
                        } else {
                            fg = color(attribute.startsWith("fg:") ? attribute.substring(3) : attribute);
                        }
                    }
                }
            }
            return new Style(fg, bg, bold, underline, strike);
        }

        private static int color(String hex) {
            if (!hex.matches("#[0-9a-f]{6}")) {
                throw new IllegalArgumentException("Expected a color like #a0b1c2, bold, underline, strike or none: "
                        + hex);
            }
            return Integer.parseInt(hex.substring(1), 16);
        }

        String open() {
            StringBuilder s = new StringBuilder();
            if (fg != null) {
                s.append(ESC).append("38;2;").append(rgb(fg)).append('m');
            }
            if (bg != null) {
                s.append(ESC).append("48;2;").append(rgb(bg)).append('m');
            }
            if (bold) {
                s.append(ESC).append("1m");
            }
            if (underline) {
                s.append(ESC).append("4m");
            }
            if (strike) {
                s.append(ESC).append("9m");
            }
            return s.toString();
        }

        String close() {
            return (strike ? ESC + "29m" : "") + (underline ? ESC + "24m" : "") + (bold ? ESC + "22m" : "")
                    + (bg != null ? ESC + "49m" : "") + (fg != null ? ESC + "39m" : "");
        }

        /**
         * The text in this style. Styles nest: where a style inside the text ends an attribute this style sets too,
         * this style's value is restored.
         */
        String apply(String text) {
            String open = open();
            if (open.isEmpty() || text.isEmpty()) {
                return text;
            }
            String inner = text;
            if (fg != null) {
                inner = inner.replace(ESC + "39m", ESC + "38;2;" + rgb(fg) + "m");
            }
            if (bg != null) {
                inner = inner.replace(ESC + "49m", ESC + "48;2;" + rgb(bg) + "m");
            }
            if (bold) {
                inner = inner.replace(ESC + "22m", ESC + "22m" + ESC + "1m");
            }
            if (underline) {
                inner = inner.replace(ESC + "24m", ESC + "4m");
            }
            if (strike) {
                inner = inner.replace(ESC + "29m", ESC + "9m");
            }
            return open + inner + close();
        }

        private static String rgb(int color) {
            return (color >> 16 & 0xff) + ";" + (color >> 8 & 0xff) + ";" + (color & 0xff);
        }
    }

    private final Map<Role, Style> styles;
    private final boolean ansi;

    private Theme(Map<Role, Style> styles, boolean ansi) {
        this.styles = styles;
        this.ansi = ansi;
    }

    /**
     * The default theme: pastel colors for a dark terminal.
     *
     * @return the theme
     */
    public static Theme defaults() {
        return of(Map.of());
    }

    /**
     * Reads a theme from a properties file; the roles left out keep their default style.
     *
     * @param file the properties file
     * @return the theme
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file names an unknown role or attribute
     */
    public static Theme load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Map<String, String> specs = new HashMap<>();
        properties.stringPropertyNames().forEach(key -> specs.put(key, properties.getProperty(key)));
        return of(specs);
    }

    /** The default theme with the styles of some roles, keyed by lower case role names, replaced. */
    static Theme of(Map<String, String> specs) {
        Map<Role, Style> styles = new EnumMap<>(Role.class);
        DEFAULTS.forEach((role, spec) -> styles.put(role, Style.parse(spec)));
        specs.forEach((key, spec) -> {
            Role role;
            try {
                role = Role.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown role in theme: " + key);
            }
            styles.put(role, Style.parse(spec));
        });
        return new Theme(styles, true);
    }

    /** The text in the style of the role. */
    String style(Role role, String text) {
        return ansi ? styles.get(role).apply(text) : text;
    }

    /** The old text, struck through. */
    String strike(String text) {
        return ansi ? style(Role.STRIKE, text) : "~~" + text + "~~";
    }

    /** The added text, without colors shown as {@code __text__}. */
    String added(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return ansi ? style(Role.REPLACEMENT, text) : "__" + text + "__";
    }

    /** The old text struck through, followed by the new text which replaced it. */
    String replace(String oldText, String newText) {
        return strike(oldText) + " " + added(newText);
    }

    /** The {@code +} or {@code -} starting a line of added or removed code. */
    String sign(boolean added) {
        return style(added ? Role.ADDED_MARK : Role.REMOVED_MARK, added ? "+" : "-");
    }

    /** The {@code ~} starting the title of a changed file. */
    String modified() {
        return style(Role.CHANGED_MARK, "~");
    }

    /** The {@code →} starting the title of a renamed file. */
    String renamed() {
        return style(Role.CHANGED_MARK, "\u2192");
    }

    /** A line of added ({@code added == true}) or removed code, starting with its {@code +} or {@code -}. */
    String changed(boolean added, String text) {
        String marked = sign(added) + text.substring(1);
        return style(added ? Role.ADDED : Role.REMOVED, marked);
    }

    /**
     * Java code with its tokens in the styles of their roles. Escape codes already in the code are kept, and text
     * already {@link #strike struck through} is left as it is.
     */
    String code(String code) {
        if (!ansi) {
            return code;
        }
        Style strike = styles.get(Role.STRIKE);
        String strikeOpen = strike.open();
        String strikeClose = strike.close();
        StringBuilder out = new StringBuilder();
        int length = code.length();
        int i = 0;
        while (i < length) {
            char c = code.charAt(i);
            int start = i;
            if (!strikeOpen.isEmpty() && code.startsWith(strikeOpen, i)) {
                int close = code.indexOf(strikeClose, i + strikeOpen.length());
                i = close < 0 ? length : close + strikeClose.length();
                out.append(code, start, i);
            } else if (c == '\u001b') {
                int m = code.indexOf('m', i);
                i = m < 0 ? length : m + 1;
                out.append(code, start, i);
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < length && code.charAt(i) != c) {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, length);
                out.append(style(Role.STRING, code.substring(start, i)));
            } else if (c == '/' && i + 1 < length && (code.charAt(i + 1) == '/' || code.charAt(i + 1) == '*')) {
                int end = code.charAt(i + 1) == '/' ? code.indexOf('\n', i) : code.indexOf("*/", i + 2);
                i = end < 0 ? length : code.charAt(i + 1) == '/' ? end : end + 2;
                out.append(style(Role.COMMENT, code.substring(start, i)));
            } else if (Character.isDigit(c)) {
                while (i < length && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '_'
                        || code.charAt(i) == '.' && i + 1 < length && Character.isDigit(code.charAt(i + 1)))) {
                    i++;
                }
                out.append(style(Role.NUMBER, code.substring(start, i)));
            } else if (c == '@' && i + 1 < length && Character.isJavaIdentifierStart(code.charAt(i + 1))) {
                i++;
                while (i < length && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
                    i++;
                }
                out.append(style(Role.ANNOTATION, code.substring(start, i)));
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < length && Character.isJavaIdentifierPart(code.charAt(i)) && code.charAt(i) != '\u001b') {
                    i++;
                }
                String word = code.substring(start, i);
                out.append(role(word, code, i).map(r -> style(r, word)).orElse(word));
            } else if (c == '…') {
                out.append(style(Role.NOTE, "…"));
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The role of a word, judged by the word and by what follows it. */
    private static Optional<Role> role(String word, String code, int after) {
        if (KEYWORDS.contains(word)) {
            return Optional.of(Role.KEYWORD);
        }
        if (next(code, after) == '(') {
            return Optional.of(Role.METHOD);
        }
        if (word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT)) && !word.equals("_")
                && word.chars().anyMatch(Character::isLetter)) {
            return Optional.of(Role.CONSTANT);
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return Optional.of(Role.TYPE);
        }
        return Optional.empty();
    }

    /** The first character from the index on that is neither a space nor part of an escape code. */
    private static char next(String code, int index) {
        int i = index;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\u001b') {
                int m = code.indexOf('m', i);
                i = m < 0 ? code.length() : m + 1;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                return c;
            }
        }
        return 0;
    }
}
