/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import org.maraboo.fleck.css.CssValues;
import org.maraboo.fleck.css.Palette;
import org.maraboo.fleck.css.Style;
import org.maraboo.fleck.css.StyleSheet;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * The look of the report: the style sheets for its {@link Css} classes, the colors taken from a {@link Palette}.
 * <p>
 * A theme can be read from a properties file whose keys are the names of the roles of the text in lower case (see
 * {@link Role}) and whose values are space separated attributes: {@code #rrggbb} or {@code fg:#rrggbb} for the text
 * color, {@code bg:#rrggbb} for the background, {@code bold}, {@code underline} and {@code strike}, or {@code none}
 * for no style, e.g.
 * <pre>
 * keyword = #82aaff bold
 * added   = bg:#1e3a2a
 * </pre>
 * Roles left out keep their default style.
 */
public final class ReportStyles {

    /** The roles of the text a theme sets the styles of, each with the selectors of its elements. */
    enum Role {
        KEYWORD(".keyword"),
        TYPE(".type"),
        METHOD(".method"),
        STRING(".string"),
        NUMBER(".number"),
        CONSTANT(".constant"),
        ANNOTATION(".annotation"),
        COMMENT(".comment"),
        /** Old text replaced by new one. */
        STRIKE(".strike"),
        /** New text following the struck through text it replaced. */
        REPLACEMENT(".added"),
        /** A renamed or moved name shown as it is now, the old name in its title. */
        RENAMED(".renamed"),
        /** Lines of added code. */
        ADDED(".add-line > .code"),
        /** Lines of removed code. */
        REMOVED(".remove-line > .code"),
        /** The {@code +} starting a line of added code, and the number of the line. */
        ADDED_MARK(".add-mark, .add-line > .ln"),
        /** The {@code -} starting a line of removed code, and the number of the line. */
        REMOVED_MARK(".remove-mark, .remove-line > .ln"),
        DECLARATION(".declaration"),
        HEADING(".heading"),
        HEADING_MARK(".heading-mark"),
        /** The {@code ~} starting the title of a changed file or a changed line, and the number of the line. */
        CHANGED_MARK(".change-mark, .change-line > .ln"),
        TITLE(".title"),
        PACKAGE(".package"),
        TAG(".tag"),
        ERROR(".error"),
        LINE_NUMBER(".ln"),
        TREE(".tree"),
        NOTE(".note"),
        NO_CHANGES(".no-changes"),
        /** The name of a level of the changes of a file. */
        LEVEL(".level");

        final String selectors;

        Role(String selectors) {
            this.selectors = selectors;
        }
    }

    private ReportStyles() {
    }

    /**
     * The default colors, pastel ones meant for a dark terminal.
     *
     * @return a new palette
     */
    public static Palette palette() {
        Palette p = new Palette("sedi");
        p.add(color("#82aaff"), "keyword");
        p.add(color("#7fd1b9"), "type");
        p.add(color("#f2e2a0"), "method");
        p.add(color("#b8e0a0"), "string");
        p.add(color("#f7c59f"), "number", "constant");
        p.add(color("#a6e3e9"), "annotation");
        p.add(color("#7f8796"), "comment", "tag");
        // Removed text on red and added text on green, enough to stand out of the dark background.
        p.add(color("#d8aeb3"), "strike");
        p.add(color("#4f242a"), "strike-background");
        p.add(color("#1c4429"), "added-background");
        // A rename is no change of the code, it is only told apart: a muted yellow.
        p.add(color("#4a4426"), "renamed-background");
        p.add(color("#5fd787"), "added-mark");
        p.add(color("#ff6b78"), "removed-mark");
        p.add(color("#e5c07b"), "changed-mark");
        p.add(color("#9cc4ff"), "heading-mark");
        p.add(color("#e4ecf7"), "title");
        p.add(color("#8a93a5"), "package", "note");
        // White on bright red: code which does not compile must not be missed.
        p.add(color("#ffffff"), "error");
        p.add(color("#e0102a"), "error-background");
        p.add(color("#5c6370"), "line-number", "tree");
        p.add(color("#ff9e3d"), "no-changes");
        return p;
    }

    /**
     * The style sheet of the report in colors.
     *
     * @param palette the colors, see {@link #palette()}
     * @return a new style sheet
     */
    public static StyleSheet styleSheet(Palette palette) {
        return styleSheet(palette, Map.of());
    }

    /**
     * The style sheet of a report without colors, for a plain console: the struck through text is shown as
     * {@code ~~text~~}, the added text as {@code __text__}.
     *
     * @return a new style sheet
     */
    public static StyleSheet plain() {
        StyleSheet ss = new StyleSheet();
        ss.add(".strike::before, .strike::after").content("~~");
        ss.add(".added::before, .added::after").content("__");
        return ss;
    }

    /**
     * Reads a theme from a properties file: the style sheet in the default colors with the styles of some roles
     * replaced.
     *
     * @param file the properties file
     * @return a new style sheet
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file names an unknown role or attribute
     */
    public static StyleSheet load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Map<String, String> specs = new HashMap<>();
        properties.stringPropertyNames().forEach(key -> specs.put(key, properties.getProperty(key)));
        return styleSheet(palette(), specs);
    }

    /**
     * The style sheet in the colors of the palette with the styles of some roles, keyed by lower case role names,
     * replaced as described in {@link ReportStyles}.
     */
    static StyleSheet styleSheet(Palette palette, Map<String, String> specs) {
        Map<Role, String> replaced = new HashMap<>();
        specs.forEach((key, spec) -> {
            Role role;
            try {
                role = Role.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown role in theme: " + key);
            }
            replaced.put(role, spec);
        });
        StyleSheet ss = new StyleSheet();
        for (Role role : Role.values()) {
            Style style = ss.add(role.selectors);
            String spec = replaced.get(role);
            if (spec == null) {
                defaults(role).accept(style, palette);
            } else {
                parse(spec, style);
            }
        }
        return ss;
    }

    /** Sets the default style of a role. */
    private static BiConsumer<Style, Palette> defaults(Role role) {
        return switch (role) {
            case KEYWORD -> fg("keyword");
            case TYPE -> fg("type");
            case METHOD -> fg("method");
            case STRING -> fg("string");
            case NUMBER -> fg("number");
            case CONSTANT -> fg("constant");
            case ANNOTATION -> fg("annotation");
            case COMMENT -> fg("comment");
            case STRIKE -> (s, p) -> s.foreground(p.get("strike")).background(p.get("strike-background"))
                    .textDecoration(CssValues.TextDecoration.STRIKETHROUGH);
            case REPLACEMENT -> (s, p) -> s.background(p.get("added-background"))
                    .textDecoration(CssValues.TextDecoration.UNDERLINE);
            case RENAMED -> (s, p) -> s.background(p.get("renamed-background"));
            case ADDED, REMOVED -> (s, p) -> { };
            case ADDED_MARK -> bold("added-mark");
            case REMOVED_MARK -> bold("removed-mark");
            case CHANGED_MARK -> bold("changed-mark");
            case DECLARATION -> (s, p) -> s.fontStyle(CssValues.FontStyle.BOLD);
            case HEADING -> (s, p) -> s.fontStyle(CssValues.FontStyle.BOLD)
                    .textDecoration(CssValues.TextDecoration.UNDERLINE);
            case HEADING_MARK -> fg("heading-mark");
            case TITLE -> bold("title");
            case PACKAGE -> fg("package");
            case TAG -> fg("tag");
            case ERROR -> (s, p) -> s.foreground(p.get("error")).background(p.get("error-background"))
                    .fontStyle(CssValues.FontStyle.BOLD);
            case LINE_NUMBER -> fg("line-number");
            case TREE -> fg("tree");
            case NOTE -> fg("note");
            case NO_CHANGES -> bold("no-changes");
            case LEVEL -> (s, p) -> s.fontStyle(CssValues.FontStyle.BOLD);
        };
    }

    private static BiConsumer<Style, Palette> fg(String color) {
        return (s, p) -> s.foreground(p.get(color));
    }

    private static BiConsumer<Style, Palette> bold(String color) {
        return (s, p) -> s.foreground(p.get(color)).fontStyle(CssValues.FontStyle.BOLD);
    }

    /** Sets the attributes of a theme file, see {@link ReportStyles}, to the style. */
    static void parse(String spec, Style style) {
        for (String attribute : spec.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            switch (attribute) {
                case "", "none" -> { }
                case "bold" -> style.fontStyle(CssValues.FontStyle.BOLD);
                case "underline" -> style.textDecoration(CssValues.TextDecoration.UNDERLINE);
                case "strike" -> style.textDecoration(CssValues.TextDecoration.STRIKETHROUGH);
                default -> {
                    if (attribute.startsWith("bg:")) {
                        style.background(color(attribute.substring(3)));
                    } else {
                        style.foreground(color(attribute.startsWith("fg:") ? attribute.substring(3) : attribute));
                    }
                }
            }
        }
    }

    private static CssValues.Color color(String hex) {
        if (!hex.toLowerCase(Locale.ROOT).matches("#[0-9a-f]{6}")) {
            throw new IllegalArgumentException("Expected a color like #a0b1c2, bold, underline, strike or none: "
                    + hex);
        }
        return new CssValues.Color(hex);
    }
}
