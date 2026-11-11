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
import cz.hrebejk.sedi.util.WordDiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.IdentityHashMap;
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

        /** The position of the comma in front of the position, but for spaces, {@code -1} if there is none. */
        int commaBefore(int position) {
            int i = position - 1;
            while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
                i--;
            }
            return i >= 0 && text.charAt(i) == ',' ? i : -1;
        }

        /** The position of the comma after the position, but for spaces, {@code -1} if there is none. */
        int commaAfter(int position) {
            int i = position;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return i < text.length() && text.charAt(i) == ',' ? i : -1;
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
    /** The most unchanged lines shown in a row, a negative number for any number of them. */
    private final int context;
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

    /**
     * Creates a printer of one body.
     *
     * @param changes the changes of the body
     * @param theme   the colors of the code and of the struck through old text
     * @param context the most unchanged lines shown in a row, a longer run of them is shown as a note with its number
     *                of lines; a negative number to show them all
     */
    AstPrinter(BodyChanges changes, Theme theme, int context) {
        this.changes = changes;
        this.context = context;
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
        comments();
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
            case BodyChanges.Renamed renamed -> {
                // The old name struck through in front of the new one, which is marked as added.
                newSource.insert(renamed.nameStart(), theme.strike(renamed.oldName()) + " ");
                newSource.added.set(renamed.nameStart(), renamed.nameEnd());
                showUnit(tree);
                for (Tree child : children(tree, newSource)) {
                    decorate(child, false);
                }
            }
        }
    }

    /**
     * The comments added, marked as added, and removed: lines of their own if they were on their own lines, else
     * struck through where they were.
     */
    private void comments() {
        for (BodyChanges.CommentChange comment : changes.comments()) {
            if (comment.isChanged()) {
                String oldText = oldSource.text.substring(comment.oldStart(), comment.oldEnd());
                if (!words(oldText, comment.newStart(), comment.newEnd())) {
                    newSource.insert(comment.newStart(), theme.strike(oldText.replaceAll("\\s+", " ")) + " ");
                    newSource.added.set(comment.newStart(), comment.newEnd());
                }
                for (long l = newSource.line(comment.newStart()); l <= newSource.line(comment.newEnd() - 1); l++) {
                    shown.add(l);
                }
                continue;
            }
            if (comment.isAdded()) {
                newSource.added.set(comment.newStart(), comment.newEnd());
                for (long l = newSource.line(comment.newStart()); l <= newSource.line(comment.newEnd() - 1); l++) {
                    shown.add(l);
                }
                continue;
            }
            int at = Math.min(comment.newStart(), newSource.text.length());
            if (isOnOwnLines(oldSource, comment.oldStart(), comment.oldEnd())) {
                List<Entry> entries = new ArrayList<>();
                for (long l = oldSource.line(comment.oldStart()); l <= oldSource.line(comment.oldEnd() - 1); l++) {
                    entries.add(Entry.line(oldSource, l, '-'));
                }
                block(newSource.line(at), entries);
            } else {
                String text = theme.strike(oldSource.text.substring(comment.oldStart(), comment.oldEnd())
                        .replaceAll("\\s+", " "));
                boolean leading = at > 0 && Character.isWhitespace(newSource.text.charAt(at - 1));
                newSource.insert(at, leading ? text + " " : " " + text);
                shown.add(newSource.line(at));
            }
        }
    }

    /** Whether the part of the source is alone on its lines, but for spaces. */
    private static boolean isOnOwnLines(Source source, int start, int end) {
        int lineStart = source.text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = source.text.indexOf('\n', end);
        lineEnd = lineEnd < 0 ? source.text.length() : lineEnd;
        return source.text.substring(lineStart, start).isBlank() && source.text.substring(end, lineEnd).isBlank();
    }

    /**
     * The new tree replaced the old one: the old one struck through in front of the new one, marked as added. A
     * changed string literal shows only the changed words, if it did not change too much.
     */
    private void replace(Tree tree, Tree old) {
        if (tree.getKind() == Tree.Kind.STRING_LITERAL && old.getKind() == Tree.Kind.STRING_LITERAL
                && words(oldSource.text.substring(oldSource.start(old), oldSource.end(old)),
                newSource.start(tree), newSource.end(tree))) {
            showUnit(tree);
            return;
        }
        newSource.insert(newSource.start(tree), theme.strike(oldSource.oneLine(old)) + " ");
        newSource.added.set(newSource.start(tree), newSource.end(tree));
        showUnit(tree);
    }

    /**
     * Marks the words of the new source from {@code start} to {@code end} which changed from the old text: the added
     * ones as added, the removed ones struck through in front of them.
     *
     * @return {@code false}, with nothing marked, if the texts differ too much for a word diff
     */
    private boolean words(String oldText, int start, int end) {
        List<WordDiff.Change> words = WordDiff.diff(oldText, newSource.text.substring(start, end));
        if (words == null) {
            return false;
        }
        for (WordDiff.Change change : words) {
            String removed = theme.removed(change);
            if (!removed.isEmpty()) {
                newSource.insert(start + change.start(), removed);
            }
            newSource.added.set(start + change.start(), start + change.end());
        }
        return true;
    }

    /** An added tree, shown with all of its lines; an added argument with its comma. */
    private void added(Tree tree) {
        int start = newSource.start(tree);
        int end = newSource.end(tree);
        if (!isStatement(tree)) {
            int before = newSource.commaBefore(start);
            int after = newSource.commaAfter(end);
            if (before >= newSource.from) {
                start = before;
            } else if (after >= 0 && after < newSource.to) {
                end = after + 1;
            }
        }
        newSource.added.set(start, end);
        showUnit(tree);
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
                    // A removed argument is struck through with its comma.
                    String comma = !isStatement(old) && oldSource.commaAfter(oldSource.end(old)) >= 0 ? "," : "";
                    newSource.insert(newSource.start(before), theme.strike(oldSource.oneLine(old) + comma) + " ");
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
                } else if (!isStatement(old) && oldSource.commaBefore(oldSource.start(old)) >= 0) {
                    // A removed last argument is struck through with its comma.
                    newSource.insert(position, theme.strike(", " + oldSource.oneLine(old)));
                    showUnit(parent);
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

    /** The lines of an old tree, as removed. */
    private List<Entry> oldLines(Tree tree) {
        List<Entry> entries = new ArrayList<>();
        for (long l = oldSource.firstLine(tree); l <= oldSource.lastLine(tree); l++) {
            entries.add(Entry.line(oldSource, l, '-'));
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

    /** The lines to print, in order, with the unchanged lines between them, see {@link #context}. */
    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        long first = newSource.line(newSource.from);
        long last = newSource.line(Math.max(newSource.from, newSource.to));
        long previous = first - 1;
        for (long l = first; l <= last + 1; l++) {
            List<Entry> block = block(l);
            boolean line = l <= last && shown.contains(l) && !moved.contains(l) && hasCode(l);
            if (block != null || line) {
                unchanged(entries, previous + 1, l, first, last);
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
            }
        }
        unchanged(entries, previous + 1, last + 1, first, last);
        // Blocks after the body, as of statements removed at its end.
        blocks.keySet().stream().filter(l -> l > last + 1).forEach(l -> entries.addAll(block(l)));
        return entries;
    }

    /**
     * Adds the unchanged new lines from one to another, not including the other: all of them if they are at most
     * {@link #context}, else a note with their number. Blank lines at the start and at the end of the body are left out,
     * and so is a run of blank lines too long to show.
     */
    private void unchanged(List<Entry> entries, long from, long to, long first, long last) {
        List<Long> lines = new ArrayList<>();
        for (long l = from; l < to; l++) {
            if (!moved.contains(l)) {
                lines.add(l);
            }
        }
        if (from == first) {
            while (!lines.isEmpty() && isBlank(lines.getFirst())) {
                lines.removeFirst();
            }
        }
        if (to > last) {
            while (!lines.isEmpty() && isBlank(lines.getLast())) {
                lines.removeLast();
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        if (context < 0 || lines.size() <= context) {
            for (long l : lines) {
                entries.add(Entry.line(newSource, l, ' '));
            }
        } else if (!lines.stream().allMatch(this::isBlank)) {
            entries.add(Entry.note(' ', "… (" + lines.size() + (lines.size() == 1 ? " line)" : " lines)")));
        }
    }

    /** Whether the part of the new line in the body has no text, not even a comment. */
    private boolean isBlank(long line) {
        return newSource.text.substring(newSource.lineStart(line), Math.max(newSource.lineStart(line),
                newSource.lineEnd(line))).isBlank() && inserts(newSource, line).findAny().isEmpty();
    }

    private boolean hasCode(long line) {
        return newSource.hasCode(newSource.lineStart(line), newSource.lineEnd(line))
                || inserts(newSource, line).findAny().isPresent()
                || newSource.added.nextSetBit(newSource.lineStart(line)) >= 0
                && newSource.added.nextSetBit(newSource.lineStart(line)) < newSource.lineEnd(line);
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
            // An added comment counts like code.
            if (newSource.isCode(i) || newSource.added.get(i) && !Character.isWhitespace(newSource.text.charAt(i))) {
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
}
