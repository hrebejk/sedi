/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import cz.hrebejk.sedi.analysis.BodyChanges;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prints the changes of a body as lines of code. The printer walks the trees: the text between the children of a
 * tree (keywords, operators, punctuation) is copied from the source, the children are printed recursively. Every
 * statement starts a line indented by its depth; within a line, line breaks of the source collapse to a space (none
 * next to parentheses and brackets) and comments are left out.
 * <p>
 * The marks of the {@link BodyChanges} decide what is printed: unchanged statements are left out, changed ones are
 * printed with their edited parts struck through, added and removed ones are prefixed with {@code +} and {@code -}.
 * The lines are colored by a {@link Theme}.
 */
final class AstPrinter {

    /** Added or removed compound statements longer than this are printed with their body collapsed. */
    private static final int COLLAPSE_LINES = 3;

    /** The source text of a compilation unit and the positions of its trees. */
    private record Source(CompilationUnitTree unit, SourcePositions positions, CharSequence text) {

        static Source of(CompilationUnitInfo info) {
            try {
                return new Source(info.compilationUnit(), info.trees().getSourcePositions(),
                        info.compilationUnit().getSourceFile().getCharContent(true));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        int start(Tree tree) {
            return (int) positions.getStartPosition(unit, tree);
        }

        int end(Tree tree) {
            return (int) positions.getEndPosition(unit, tree);
        }

        /** Whether the tree has its own place in the source; synthetic trees, like an implicit {@code super()}, do not. */
        boolean isPresent(Tree tree) {
            return start(tree) >= 0 && end(tree) > start(tree);
        }

        int lines(Tree tree) {
            int lines = 1;
            for (int i = start(tree); i < end(tree); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            return lines;
        }
    }

    /**
     * How trees are printed.
     *
     * @param source the version the trees are from
     * @param marked {@code true} to follow the marks of the new version, {@code false} to print the trees as they are
     * @param prefix what every line starts with after the indentation
     */
    private record Mode(Source source, boolean marked, String prefix) {
    }

    private final BodyChanges changes;
    private final Source oldSource;
    private final Source newSource;
    private final Theme theme;
    private final List<String> lines = new ArrayList<>();
    private StringBuilder line;
    private int lineDepth;
    private String linePrefix = "";

    /**
     * Creates a printer of one body.
     *
     * @param changes the changes of the body
     * @param theme   the colors of the code and of the struck through old text
     */
    AstPrinter(BodyChanges changes, Theme theme) {
        this.changes = changes;
        this.oldSource = Source.of(changes.oldUnit());
        this.newSource = Source.of(changes.newUnit());
        this.theme = theme;
    }

    /**
     * Prints the changes.
     *
     * @return the lines, indented by two spaces per level, starting at no indentation
     */
    List<String> print() {
        Mode mode = new Mode(newSource, true, "");
        if (changes.newRoot() instanceof BlockTree block) {
            statements(block, 0, mode);
        } else {
            begin(0, "");
            emit(changes.newRoot(), mode, 0, null);
            end();
        }
        return lines;
    }

    private void statements(BlockTree block, int depth, Mode mode) {
        for (StatementTree statement : block.getStatements()) {
            if (mode.marked()) {
                removed(changes.removedBefore().get(statement), depth);
            }
            statement(statement, depth, mode);
        }
        if (mode.marked()) {
            removed(changes.removedAtEnd().get(block), depth);
        }
    }

    /** Prints a statement on lines of its own. */
    private void statement(Tree tree, int depth, Mode mode) {
        if (!mode.source().isPresent(tree)) {
            return;
        }
        if (mode.marked()) {
            BodyChanges.Mark mark = changes.marks().get(tree);
            if (mark == null) {
                return;
            }
            if (mark instanceof BodyChanges.Added) {
                whole(tree, newSource, depth, "+ ");
                return;
            }
            if (mark instanceof BodyChanges.Replaced replaced) {
                whole(replaced.oldTree(), oldSource, depth, "- ");
                whole(tree, newSource, depth, "+ ");
                return;
            }
        }
        begin(depth, mode.prefix());
        emit(tree, mode, depth, null);
        end();
    }

    /** Prints a whole statement, every line with the prefix; a long compound statement is collapsed. */
    private void whole(Tree tree, Source source, int depth, String prefix) {
        if (!source.isPresent(tree)) {
            return;
        }
        Mode mode = new Mode(source, false, prefix);
        Tree body = BodyChanges.isCompound(tree) && source.lines(tree) > COLLAPSE_LINES ? firstBody(tree) : null;
        begin(depth, prefix);
        emit(tree, mode, depth, body);
        end();
    }

    private void removed(List<Tree> olds, int depth) {
        if (olds != null) {
            for (Tree old : olds) {
                whole(old, oldSource, depth, "- ");
            }
        }
    }

    /**
     * Appends a tree to the current line; blocks and nested statements continue on new lines.
     *
     * @param collapseAt the child to print as {@code { … N lines }} and stop at, {@code null} to print everything
     */
    private void emit(Tree tree, Mode mode, int depth, Tree collapseAt) {
        Source source = mode.source();
        if (mode.marked()) {
            Tree old = switch (changes.marks().get(tree)) {
                case BodyChanges.Edited edited -> edited.oldTree();
                case BodyChanges.Replaced replaced -> replaced.oldTree();
                case null, default -> null;
            };
            if (old != null) {
                append(theme.replace(plain(old, oldSource), plain(tree, source)));
                return;
            }
        }
        if (tree instanceof ClassTree) {
            // The body of an anonymous class is not diffed, just shown in short.
            append(plain(tree, source));
            return;
        }
        if (tree instanceof BlockTree block) {
            if (mode.marked() && changes.marks().get(block) == null) {
                append("{ … }");
                return;
            }
            append("{");
            end();
            statements(block, depth + 1, mode);
            begin(depth, mode.prefix());
            append("}");
            return;
        }
        int cursor = source.start(tree);
        int stop = source.end(tree);
        for (Tree child : children(tree, source)) {
            if (!source.isPresent(child) || source.start(child) < cursor || source.end(child) > stop) {
                continue;
            }
            append(gap(source, cursor, source.start(child)));
            cursor = source.end(child);
            if (child == collapseAt) {
                append("{ … " + source.lines(tree) + " lines }");
                return;
            }
            List<Tree> removed = mode.marked() ? changes.removedBefore().get(child) : null;
            if (isStatementChild(tree, child)) {
                end();
                removed(removed, depth + 1);
                statement(child, depth + 1, mode);
                begin(depth, mode.prefix());
            } else {
                if (removed != null) {
                    removed.forEach(old -> append(theme.strike(plain(old, oldSource)) + " "));
                }
                emit(child, mode, depth, null);
            }
        }
        List<Tree> removedAtEnd = mode.marked() ? changes.removedAtEnd().get(tree) : null;
        if (removedAtEnd != null) {
            for (Tree old : removedAtEnd) {
                if (old instanceof StatementTree || old instanceof CaseTree) {
                    end();
                    whole(old, oldSource, depth + 1, "- ");
                    begin(depth, mode.prefix());
                } else {
                    append(" " + theme.strike(plain(old, oldSource)));
                }
            }
        }
        append(gap(source, cursor, stop));
    }

    /** The tree on a single line, blocks shown as {@code { … }}. */
    private String plain(Tree tree, Source source) {
        if (tree instanceof BlockTree) {
            return "{ … }";
        }
        StringBuilder text = new StringBuilder();
        int cursor = source.start(tree);
        int stop = source.end(tree);
        for (Tree child : children(tree, source)) {
            if (!source.isPresent(child) || source.start(child) < cursor || source.end(child) > stop) {
                continue;
            }
            text.append(gap(source, cursor, source.start(child))).append(plain(child, source));
            cursor = source.end(child);
        }
        return text.append(gap(source, cursor, stop)).toString().strip();
    }

    /** The direct children of a tree, in the order of the source. */
    private static List<Tree> children(Tree tree, Source source) {
        List<Tree> children = new ArrayList<>();
        tree.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree child, Void unused) {
                if (child != null) {
                    children.add(child);
                }
                return null;
            }
        }, null);
        children.sort(Comparator.comparingInt(source::start));
        return children;
    }

    /** Whether the child is a statement nested in the parent, printed on lines of its own. */
    private static boolean isStatementChild(Tree parent, Tree child) {
        if (child instanceof BlockTree) {
            return false;
        }
        return switch (parent) {
            case IfTree t -> child == t.getThenStatement() || child == t.getElseStatement();
            case ForLoopTree t -> child == t.getStatement();
            case EnhancedForLoopTree t -> child == t.getStatement();
            case WhileLoopTree t -> child == t.getStatement();
            case DoWhileLoopTree t -> child == t.getStatement();
            case LabeledStatementTree t -> child == t.getStatement();
            case SwitchTree t -> child instanceof CaseTree;
            case SwitchExpressionTree t -> child instanceof CaseTree;
            case CaseTree t -> t.getStatements() != null && child instanceof StatementTree;
            default -> false;
        };
    }

    /** The first block (or case, or catch) of a compound statement, where its header ends. */
    private static Tree firstBody(Tree tree) {
        List<Tree> children = new ArrayList<>();
        tree.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree child, Void unused) {
                if (child instanceof BlockTree || child instanceof CaseTree || child instanceof CatchTree) {
                    children.add(child);
                }
                return null;
            }
        }, null);
        return children.isEmpty() ? null : children.getFirst();
    }

    /**
     * The source between two trees: line breaks and comments become a single space, other spacing is kept.
     */
    private static String gap(Source source, int from, int to) {
        CharSequence text = source.text();
        StringBuilder result = new StringBuilder();
        StringBuilder space = new StringBuilder();
        boolean collapse = false;
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            char next = i + 1 < to ? text.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                while (i < to && text.charAt(i) != '\n') {
                    i++;
                }
                collapse = true;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < to && !(text.charAt(i - 1) == '*' && text.charAt(i) == '/')) {
                    i++;
                }
                i++;
                collapse = true;
            } else if (Character.isWhitespace(c)) {
                space.append(c);
                collapse |= c == '\n';
                i++;
            } else {
                result.append(collapse ? (isTight(result, c) ? "" : " ") : space);
                space.setLength(0);
                collapse = false;
                result.append(c);
                i++;
            }
        }
        return result.append(collapse ? (isTight(result, ' ') ? "" : " ") : space).toString();
    }

    /** Whether a collapsed line break between the text and the next character is better left out, as in {@code f(a)} or
     * {@code a.b()}. */
    private static boolean isTight(StringBuilder before, char next) {
        char last = before.isEmpty() ? ' ' : before.charAt(before.length() - 1);
        return last == '(' || last == '[' || last == '.' || next == ')' || next == ']' || next == ',' || next == ';'
                || next == '.';
    }

    private void begin(int depth, String prefix) {
        end();
        line = new StringBuilder();
        lineDepth = depth;
        linePrefix = prefix;
    }

    private void append(String text) {
        if (line == null) {
            begin(lineDepth, linePrefix);
        }
        line.append(text);
    }

    private void end() {
        if (line != null) {
            String text = line.toString().strip();
            if (!text.isEmpty()) {
                String code = theme.code(text);
                lines.add("  ".repeat(lineDepth) + switch (linePrefix) {
                    case "+ " -> theme.changed(true, linePrefix + code);
                    case "- " -> theme.changed(false, linePrefix + code);
                    default -> linePrefix + code;
                });
            }
            line = null;
        }
    }
}
