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
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import cz.hrebejk.sedi.analysis.BodyChanges;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Prints the changes of a body as the lines of the source, as they are: their spacing, line breaks, braces and
 * comments. Only the lines with changes are printed, with the lines of the statements around them which hold them, like
 * {@code if (c) {} and its {@code }}; skipped lines are shown as {@code …}.
 * <p>
 * The changes are put on the lines of the new version: added code is underlined, old code which was replaced or
 * removed is struck through in front of it. Statements added or removed on lines of their own are printed as such
 * lines, the removed ones from the old version. Every line is numbered by its line in its version and marked by
 * {@code +} if it was added, {@code -} if removed, {@code ~} if changed, see {@link LineNumbers}. The lines are colored
 * by a {@link Theme}.
 */
final class AstPrinter {

    /** Added or removed compound statements longer than this are printed with their body collapsed. */
    private static final int COLLAPSE_LINES = 3;
    private static final int TAB_WIDTH = 4;

    /** The source of a version: its text, the positions of its trees and the region of the body in it. */
    private static final class Source {

        final CompilationUnitTree unit;
        final SourcePositions positions;
        final LineMap lines;
        final String text;
        /** The region of the body: inside the braces of a block, the whole expression of a field initializer. */
        final int from;
        final int to;
        final BitSet comments = new BitSet();
        /** String and character literals and text blocks, whose spaces are kept. */
        final BitSet literals = new BitSet();
        final BitSet added = new BitSet();
        final BitSet errors = new BitSet();
        /** Text put in front of the character at a position, like old code struck through. */
        final Map<Integer, List<String>> inserts = new TreeMap<>();

        Source(CompilationUnitInfo info, Tree root) {
            unit = info.compilationUnit();
            positions = info.trees().getSourcePositions();
            lines = unit.getLineMap();
            try {
                text = unit.getSourceFile().getCharContent(true).toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            boolean block = root instanceof BlockTree;
            from = start(root) + (block ? 1 : 0);
            to = end(root) - (block ? 1 : 0);
            findComments();
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

        long line(int position) {
            return lines.getLineNumber(position);
        }

        long firstLine(Tree tree) {
            return line(start(tree));
        }

        long lastLine(Tree tree) {
            return line(end(tree) - 1);
        }

        /** The start of the part of the line in the body. */
        int lineStart(long line) {
            return Math.max((int) lines.getStartPosition(line), from);
        }

        /** The end of the part of the line in the body, not including the line break. */
        int lineEnd(long line) {
            int end = text.indexOf('\n', (int) lines.getStartPosition(line));
            return Math.min(end < 0 ? text.length() : end, to);
        }

        /** Whether the line starts before the body, like the line of the declaration of a method on one line. */
        boolean clipped(long line) {
            return lines.getStartPosition(line) < from;
        }

        /** Whether the character is code: neither a space nor in a comment. */
        boolean isCode(int position) {
            return !Character.isWhitespace(text.charAt(position)) && !comments.get(position);
        }

        boolean hasCode(int from, int to) {
            for (int i = Math.max(from, this.from); i < Math.min(to, this.to); i++) {
                if (isCode(i)) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the tree is alone on its lines, but for spaces and comments. */
        boolean isOnOwnLines(Tree tree) {
            return !hasCode(lineStart(firstLine(tree)), start(tree)) && !hasCode(end(tree), lineEnd(lastLine(tree)));
        }

        /** The text of a part of the source on a single line: comments left out, spaces collapsed. */
        String oneLine(int from, int to) {
            StringBuilder out = new StringBuilder();
            boolean space = false;
            for (int i = from; i < to; i++) {
                if (comments.get(i) || Character.isWhitespace(text.charAt(i)) && !literals.get(i)) {
                    space = true;
                } else {
                    if (space && !out.isEmpty()) {
                        out.append(' ');
                    }
                    space = false;
                    out.append(text.charAt(i));
                }
            }
            return out.toString();
        }

        String oneLine(Tree tree) {
            return oneLine(start(tree), end(tree));
        }

        void insert(int position, String text) {
            inserts.computeIfAbsent(position, p -> new ArrayList<>()).add(text);
        }

        /** Finds the comments in the body, skipping string and character literals and text blocks. */
        private void findComments() {
            int i = Math.max(from, 0);
            while (i < to) {
                char c = text.charAt(i);
                char next = i + 1 < to ? text.charAt(i + 1) : 0;
                if (c == '/' && next == '/') {
                    int end = text.indexOf('\n', i);
                    end = end < 0 || end > to ? to : end;
                    comments.set(i, end);
                    i = end;
                } else if (c == '/' && next == '*') {
                    int end = text.indexOf("*/", i + 2);
                    end = end < 0 || end + 2 > to ? to : end + 2;
                    comments.set(i, end);
                    i = end;
                } else if (text.startsWith("\"\"\"", i)) {
                    int end = text.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? to : Math.min(end + 3, to);
                    literals.set(i, end);
                    i = end;
                } else if (c == '"' || c == '\'') {
                    int start = i;
                    i++;
                    while (i < to && text.charAt(i) != c && text.charAt(i) != '\n') {
                        i += text.charAt(i) == '\\' ? 2 : 1;
                    }
                    i = Math.min(i + 1, to);
                    literals.set(start, i);
                } else {
                    i++;
                }
            }
        }
    }

    /** A line of the output: a line of a version, or a remark. */
    private record Entry(Source source, long line, char marker, String note) {

        static Entry line(Source source, long line, char marker) {
            return new Entry(source, line, marker, null);
        }

        static Entry note(char marker, String note) {
            return new Entry(null, 0, marker, note);
        }
    }

    private final BodyChanges changes;
    private final Source oldSource;
    private final Source newSource;
    private final Theme theme;
    /** The statement, or the outermost expression in the header of a compound statement, each new tree is part of. */
    private final Map<Tree, Tree> units = new IdentityHashMap<>();
    /** The new lines to print. */
    private final Set<Long> shown = new TreeSet<>();
    /** New lines printed in a block of old lines instead of in their place. */
    private final Set<Long> moved = new TreeSet<>();
    /**
     * Groups of old lines, some with new lines among them, printed in front of a new line. The groups are printed in
     * the order of their old lines.
     */
    private final Map<Long, List<List<Entry>>> blocks = new TreeMap<>();
    /** The first and the last line of a collapsed added statement, by the last line of its header. */
    private final Map<Long, long[]> collapsed = new LinkedHashMap<>();

    /**
     * Creates a printer of one body.
     *
     * @param changes the changes of the body
     * @param theme   the colors of the code and of the struck through old text
     */
    AstPrinter(BodyChanges changes, Theme theme) {
        this.changes = changes;
        this.oldSource = new Source(changes.oldUnit(), changes.oldRoot());
        this.newSource = new Source(changes.newUnit(), changes.newRoot());
        this.theme = theme;
        for (Tree tree : changes.unresolved()) {
            if (newSource.isPresent(tree) && newSource.start(tree) >= newSource.from
                    && newSource.end(tree) <= newSource.to) {
                newSource.errors.set(newSource.start(tree), newSource.end(tree));
            }
        }
    }

    /**
     * Prints the changes.
     *
     * @return the lines, numbered (see {@link LineNumbers}), indented by the source relative to the least indented
     *         of them
     */
    List<String> print() {
        findUnits(changes.newRoot(), null);
        decorate(changes.newRoot(), true);
        removed();
        return render(entries());
    }

    /** Finds the unit of every new tree, see {@link #units}. */
    private void findUnits(Tree tree, Tree unit) {
        Tree own = unit;
        if (tree instanceof BlockTree || tree instanceof CaseTree || tree instanceof CatchTree) {
            own = null;
        } else if (tree instanceof StatementTree && !BodyChanges.isCompound(tree)) {
            own = tree;
        } else if (own == null && tree instanceof ExpressionTree) {
            own = tree;
        }
        units.put(tree, own == null ? tree : own);
        for (Tree child : children(tree, newSource)) {
            findUnits(child, own);
        }
    }

    /** Shows all the lines of the unit of the tree. */
    private void showUnit(Tree tree) {
        Tree unit = units.getOrDefault(tree, tree);
        showLines(unit);
    }

    private void showLines(Tree tree) {
        if (newSource.isPresent(tree)) {
            for (long l = newSource.firstLine(tree); l <= newSource.lastLine(tree); l++) {
                shown.add(l);
            }
        }
    }

    /** Puts the marks of the tree and of the trees in it on the new source. */
    private void decorate(Tree tree, boolean root) {
        if (!newSource.isPresent(tree)) {
            return;
        }
        BodyChanges.Mark mark = changes.marks().get(tree);
        int start = newSource.start(tree);
        int end = newSource.end(tree);
        switch (mark) {
            case null -> {
            }
            case BodyChanges.Changed changed -> {
                if (!root) {
                    ownLines(tree);
                }
                for (Tree child : children(tree, newSource)) {
                    decorate(child, false);
                }
            }
            case BodyChanges.Edited edited -> replace(tree, edited.oldTree());
            case BodyChanges.Replaced replaced -> {
                if (isStatement(tree) && isStatement(replaced.oldTree()) && newSource.isOnOwnLines(tree)
                        && oldSource.isOnOwnLines(replaced.oldTree())) {
                    block(newSource.firstLine(tree), oldLines(replaced.oldTree()));
                    added(tree);
                } else {
                    replace(tree, replaced.oldTree());
                }
            }
            case BodyChanges.Added added -> added(tree);
            case BodyChanges.Wrapped wrapped -> {
                newSource.added.set(start, newSource.start(wrapped.kept()));
                newSource.added.set(newSource.end(wrapped.kept()), end);
                showUnit(tree);
            }
            case BodyChanges.Unwrapped unwrapped -> {
                Tree old = unwrapped.oldTree();
                Tree kept = unwrapped.oldKept();
                String before = oldSource.oneLine(oldSource.start(old), oldSource.start(kept));
                String after = oldSource.oneLine(oldSource.end(kept), oldSource.end(old));
                if (!before.isEmpty()) {
                    newSource.insert(start, theme.strike(before));
                }
                if (!after.isEmpty()) {
                    newSource.insert(end, theme.strike(after));
                }
                showUnit(tree);
            }
            case BodyChanges.WrappedStatements wrapped -> {
                newSource.added.set(start, end);
                for (Tree kept : wrapped.kept()) {
                    newSource.added.clear(newSource.start(kept), newSource.end(kept));
                }
                showLines(tree);
            }
            case BodyChanges.UnwrappedStatements unwrapped -> unwrapped(unwrapped);
            case BodyChanges.Kept kept -> {
            }
        }
    }

    /** The new tree replaced the old one: the old one struck through in front of the new one, marked as added. */
    private void replace(Tree tree, Tree old) {
        newSource.insert(newSource.start(tree), theme.strike(oldSource.oneLine(old)) + " ");
        newSource.added.set(newSource.start(tree), newSource.end(tree));
        showUnit(tree);
    }

    /** An added tree; a long compound statement is collapsed to its header. */
    private void added(Tree tree) {
        newSource.added.set(newSource.start(tree), newSource.end(tree));
        showUnit(tree);
        long first = newSource.firstLine(tree);
        long last = newSource.lastLine(tree);
        Tree body = firstBody(tree);
        if (BodyChanges.isCompound(tree) && last - first + 1 > COLLAPSE_LINES && body != null) {
            long header = newSource.firstLine(body);
            for (long l = header + 1; l <= last; l++) {
                shown.remove(l);
            }
            collapsed.put(header, new long[]{first, last});
        }
    }

    /** Shows the lines with the text of the tree itself, not of the trees in it, like {@code if (}, {@code ) {}. */
    private void ownLines(Tree tree) {
        int cursor = newSource.start(tree);
        for (Tree child : children(tree, newSource)) {
            if (newSource.isPresent(child) && newSource.start(child) >= cursor) {
                ownLines(cursor, newSource.start(child));
                cursor = newSource.end(child);
            }
        }
        ownLines(cursor, newSource.end(tree));
    }

    private void ownLines(int from, int to) {
        for (int i = Math.max(from, newSource.from); i < Math.min(to, newSource.to); i++) {
            if (newSource.isCode(i)) {
                shown.add(newSource.line(i));
            }
        }
    }

    /** The old statements without a counterpart: lines of their own if they are on their own lines, else struck through. */
    private void removed() {
        changes.removedBefore().forEach((before, olds) -> {
            for (Tree old : olds) {
                if (isStatement(old) && oldSource.isOnOwnLines(old)) {
                    block(newSource.firstLine(before), oldLines(old));
                } else {
                    newSource.insert(newSource.start(before), theme.strike(oldSource.oneLine(old)) + " ");
                    showUnit(before);
                }
            }
        });
        changes.removedAtEnd().forEach((parent, olds) -> {
            List<Tree> children = children(parent, newSource).stream().filter(newSource::isPresent).toList();
            int position = children.isEmpty() ? newSource.end(parent) - 1 : newSource.end(children.getLast());
            for (Tree old : olds) {
                if (isStatement(old) && oldSource.isOnOwnLines(old)) {
                    block(newSource.line(Math.min(newSource.end(parent) - 1, newSource.to)), oldLines(old));
                } else {
                    newSource.insert(position, " " + theme.strike(oldSource.oneLine(old)));
                    if (parent == changes.newRoot()) {
                        shown.add(newSource.line(position));
                    } else {
                        showUnit(parent);
                    }
                }
            }
        });
    }

    /**
     * A removed statement around new ones: its own lines as removed and, in place of the old statements kept, the
     * new statements; if they do not have lines of their own, all the old lines, then all the new ones.
     */
    private void unwrapped(BodyChanges.UnwrappedStatements unwrapped) {
        Tree old = unwrapped.oldTree();
        List<Map.Entry<Tree, Tree>> kept = unwrapped.kept().entrySet().stream()
                .sorted(Comparator.comparingInt(e -> oldSource.start(e.getKey()))).toList();
        boolean aligned = oldSource.isOnOwnLines(old) && kept.stream().allMatch(e -> oldSource.isOnOwnLines(e.getKey())
                && newSource.isOnOwnLines(e.getValue()));
        List<Entry> entries = new ArrayList<>();
        long first = oldSource.firstLine(old);
        long last = oldSource.lastLine(old);
        long anchor = Long.MAX_VALUE;
        if (aligned) {
            long line = first;
            for (Map.Entry<Tree, Tree> e : kept) {
                for (; line < oldSource.firstLine(e.getKey()); line++) {
                    entries.add(Entry.line(oldSource, line, '-'));
                }
                entries.addAll(newLines(e.getValue()));
                line = oldSource.lastLine(e.getKey()) + 1;
            }
            for (; line <= last; line++) {
                entries.add(Entry.line(oldSource, line, '-'));
            }
        } else {
            entries.addAll(oldLines(old));
            Set<Long> lines = new TreeSet<>();
            kept.forEach(e -> {
                for (long l = newSource.firstLine(e.getValue()); l <= newSource.lastLine(e.getValue()); l++) {
                    lines.add(l);
                }
            });
            lines.forEach(l -> entries.add(Entry.line(newSource, l, ' ')));
        }
        for (Map.Entry<Tree, Tree> e : kept) {
            anchor = Math.min(anchor, newSource.firstLine(e.getValue()));
            for (long l = newSource.firstLine(e.getValue()); l <= newSource.lastLine(e.getValue()); l++) {
                moved.add(l);
            }
        }
        block(anchor, entries);
    }

    /** The lines of a new tree as they are. */
    private List<Entry> newLines(Tree tree) {
        List<Entry> entries = new ArrayList<>();
        for (long l = newSource.firstLine(tree); l <= newSource.lastLine(tree); l++) {
            entries.add(Entry.line(newSource, l, ' '));
        }
        return entries;
    }

    /** The lines of an old tree, as removed; a long compound statement is collapsed to its header. */
    private List<Entry> oldLines(Tree tree) {
        List<Entry> entries = new ArrayList<>();
        long first = oldSource.firstLine(tree);
        long last = oldSource.lastLine(tree);
        Tree body = firstBody(tree);
        if (BodyChanges.isCompound(tree) && last - first + 1 > COLLAPSE_LINES && body != null) {
            for (long l = first; l <= oldSource.firstLine(body); l++) {
                entries.add(Entry.line(oldSource, l, '-'));
            }
            entries.add(Entry.note('-', "… " + (last - first + 1) + " lines"));
        } else {
            for (long l = first; l <= last; l++) {
                entries.add(Entry.line(oldSource, l, '-'));
            }
        }
        return entries;
    }

    private void block(long beforeLine, List<Entry> entries) {
        blocks.computeIfAbsent(beforeLine, l -> new ArrayList<>()).add(entries);
    }

    /** The groups of lines printed in front of a new line, in the order of their old lines. */
    private List<Entry> block(long beforeLine) {
        List<List<Entry>> groups = blocks.get(beforeLine);
        if (groups == null) {
            return null;
        }
        return groups.stream()
                .sorted(Comparator.comparingLong(g -> g.stream().filter(e -> e.source() == oldSource)
                        .mapToLong(Entry::line).findFirst().orElse(Long.MAX_VALUE)))
                .flatMap(List::stream).toList();
    }

    /** The lines to print, in order, with {@code …} where new lines are skipped. */
    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        long first = newSource.line(newSource.from);
        long last = newSource.line(Math.max(newSource.from, newSource.to));
        long previous = -1;
        for (long l = first; l <= last + 1; l++) {
            List<Entry> block = block(l);
            boolean line = l <= last && shown.contains(l) && !moved.contains(l) && hasCode(l);
            if (block != null || line) {
                if (previous >= 0 && hasCode(previous + 1, l)) {
                    entries.add(Entry.note(' ', "…"));
                }
            }
            if (block != null) {
                entries.addAll(block);
                for (Entry e : block) {
                    if (e.source() == newSource) {
                        previous = Math.max(previous, e.line());
                    }
                }
                previous = Math.max(previous, l - 1);
            }
            if (line) {
                entries.add(Entry.line(newSource, l, marker(l)));
                previous = l;
                long[] statement = collapsed.get(l);
                if (statement != null) {
                    entries.add(Entry.note('+', "… " + (statement[1] - statement[0] + 1) + " lines"));
                    previous = statement[1];
                    l = statement[1];
                }
            }
        }
        // Blocks after the body, as of statements removed at its end.
        blocks.keySet().stream().filter(l -> l > last + 1).forEach(l -> entries.addAll(block(l)));
        return entries;
    }

    /** Whether there is code on the new lines from one to another, not including the other. */
    private boolean hasCode(long from, long to) {
        for (long l = from; l < to; l++) {
            if (hasCode(l)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCode(long line) {
        return newSource.hasCode(newSource.lineStart(line), newSource.lineEnd(line))
                || inserts(newSource, line).findAny().isPresent();
    }

    private java.util.stream.Stream<String> inserts(Source source, long line) {
        int from = source.lineStart(line);
        int to = source.lineEnd(line);
        return source.inserts.entrySet().stream().filter(e -> e.getKey() >= from && e.getKey() <= to)
                .flatMap(e -> e.getValue().stream());
    }

    /** {@code +} for a line of added code alone, {@code ~} for a line with changes, else a space. */
    private char marker(long line) {
        int from = newSource.lineStart(line);
        int to = newSource.lineEnd(line);
        boolean code = false;
        boolean allAdded = true;
        boolean anyAdded = false;
        for (int i = from; i < to; i++) {
            if (newSource.isCode(i)) {
                code = true;
                allAdded &= newSource.added.get(i);
                anyAdded |= newSource.added.get(i);
            }
        }
        boolean inserted = inserts(newSource, line).findAny().isPresent();
        if (code && allAdded && !inserted) {
            return '+';
        }
        return anyAdded || inserted ? '~' : ' ';
    }

    /** The lines of the entries, indented relative to the least indented of them. */
    private List<String> render(List<Entry> entries) {
        int indent = Integer.MAX_VALUE;
        for (Entry e : entries) {
            if (e.note() == null && !e.source().clipped(e.line())) {
                indent = Math.min(indent, indentation(e.source(), e.line()));
            }
        }
        indent = indent == Integer.MAX_VALUE ? 0 : indent;
        List<String> lines = new ArrayList<>();
        for (Entry e : entries) {
            if (e.note() != null) {
                lines.add(LineNumbers.number(0, e.marker(), theme.style(Theme.Role.NOTE, e.note())));
            } else {
                // A line of added code is marked by its +, the code itself is not.
                String text = text(e.source(), e.line(), indent, e.marker() != '+');
                lines.add(LineNumbers.number(e.line(), e.marker(), theme.code(text)));
            }
        }
        return lines;
    }

    /** The columns of the spaces starting the line, tabs expanded. */
    private static int indentation(Source source, long line) {
        int column = 0;
        for (int i = source.lineStart(line); i < source.lineEnd(line); i++) {
            char c = source.text.charAt(i);
            if (c == '\t') {
                column += TAB_WIDTH - column % TAB_WIDTH;
            } else if (c == ' ') {
                column++;
            } else {
                return column;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The line with its marks, without the given indentation, tabs expanded. */
    private String text(Source source, long line, int indent, boolean markAdded) {
        int from = source.lineStart(line);
        int to = source.lineEnd(line);
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        int runFlags = -1;
        int column = 0;
        boolean leading = true;
        for (int i = from; i <= to; i++) {
            char c = i < to ? source.text.charAt(i) : '\n';
            if (leading && (c == ' ' || c == '\t')) {
                column += c == '\t' ? TAB_WIDTH - column % TAB_WIDTH : 1;
                continue;
            }
            if (leading) {
                leading = false;
                int spaces = source.clipped(line) ? 0 : Math.max(0, column - indent);
                out.append(" ".repeat(spaces));
            }
            List<String> inserted = source.inserts.get(i);
            if (inserted != null) {
                out.append(style(run.toString(), runFlags));
                run.setLength(0);
                runFlags = -1;
                inserted.forEach(out::append);
            }
            if (i == to) {
                break;
            }
            int flags = (source.comments.get(i) ? 1 : 0) | (markAdded && source.added.get(i) ? 2 : 0)
                    | (source.errors.get(i) ? 4 : 0);
            if (flags != runFlags) {
                out.append(style(run.toString(), runFlags));
                run.setLength(0);
                runFlags = flags;
            }
            if (c == '\t') {
                run.append(" ".repeat(TAB_WIDTH - column % TAB_WIDTH));
                column += TAB_WIDTH - column % TAB_WIDTH;
            } else {
                run.append(c);
                column++;
            }
        }
        out.append(style(run.toString(), runFlags));
        return out.toString().stripTrailing();
    }

    /** A run of characters in the styles of its flags: 1 a comment, 2 added, 4 an error. */
    private String style(String text, int flags) {
        if (text.isEmpty() || flags <= 0) {
            return text;
        }
        String styled = (flags & 1) != 0 ? theme.style(Theme.Role.COMMENT, text) : text;
        styled = (flags & 4) != 0 ? theme.error(styled) : styled;
        if ((flags & 2) != 0) {
            // The spaces around added code are not marked.
            String stripped = styled.strip();
            if (stripped.isEmpty()) {
                return styled;
            }
            int start = styled.indexOf(stripped);
            return styled.substring(0, start) + theme.added(stripped) + styled.substring(start + stripped.length());
        }
        return styled;
    }

    private static boolean isStatement(Tree tree) {
        return tree instanceof StatementTree || tree instanceof CaseTree;
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
}
