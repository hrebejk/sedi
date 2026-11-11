/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word diff of two texts, like two versions of a string literal or of a comment. The texts are split into words
 * (runs of letters, digits and underscores) and single other characters, like punctuation and quotes; whitespace
 * only separates them. Only the words count for how {@link #similarity similar} the texts are. The changes are positions in the new text, so the new text can be shown as it is, with the
 * added words marked and the removed ones put in front of them.
 */
public final class WordDiff {

    /** The least {@link #similarity} of two texts for a word diff to be easier to read than the old and the new text. */
    public static final double SIMILAR = 0.5;

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+|\\S");

    private WordDiff() {
    }

    /**
     * A change: the old text {@code removed} was replaced by the new text from {@code start} to {@code end}.
     *
     * @param start   where the change is in the new text
     * @param end     the end of the added text, {@code start} if nothing was added
     * @param removed the removed old text as it was, empty if nothing was removed
     * @param before  a space to put in front of the removed text, or an empty string, as the old text had one there
     * @param after   a space to put after the removed text, or an empty string
     */
    public record Change(int start, int end, String removed, String before, String after) {
    }

    private record Token(int start, int end, String text) {

        boolean isWord() {
            return Character.isLetterOrDigit(text.codePointAt(0)) || text.charAt(0) == '_';
        }
    }

    /**
     * How many of the words of the two texts are common, from 0 to 1.
     *
     * @param oldText the old text
     * @param newText the new text
     * @return 1 for texts with the same words, 0 for texts with no common word
     */
    public static double similarity(String oldText, String newText) {
        return similarity(tokens(oldText), tokens(newText));
    }

    /**
     * The similarity of the words, punctuation and quotes left out: two texts with only them in common, like
     * {@code "Gone."} and {@code "Fresh."}, are not similar. Texts without words are compared by all their tokens.
     */
    private static double similarity(List<Token> o, List<Token> n) {
        List<Token> oldWords = o.stream().filter(Token::isWord).toList();
        List<Token> newWords = n.stream().filter(Token::isWord).toList();
        if (!oldWords.isEmpty() || !newWords.isEmpty()) {
            o = oldWords;
            n = newWords;
        }
        return o.isEmpty() && n.isEmpty() ? 1 : 2.0 * lcs(o, n).size() / (o.size() + n.size());
    }

    /**
     * The changes turning the old text into the new one, if the texts are {@link #SIMILAR} enough.
     *
     * @param oldText the old text
     * @param newText the new text
     * @return the changes in the order of the new text, {@code null} if the texts differ too much for a word diff
     */
    public static List<Change> diff(String oldText, String newText) {
        List<Token> o = tokens(oldText);
        List<Token> n = tokens(newText);
        if (similarity(o, n) < SIMILAR) {
            return null;
        }
        List<int[]> matches = lcs(o, n);
        List<int[]> anchors = new ArrayList<>(matches);
        anchors.add(new int[]{o.size(), n.size()});
        List<Change> changes = new ArrayList<>();
        int i = 0;
        int j = 0;
        for (int[] anchor : anchors) {
            if (i < anchor[0] || j < anchor[1]) {
                changes.add(change(oldText, o.subList(i, anchor[0]), newText, n, j, anchor[1]));
            }
            i = anchor[0] + 1;
            j = anchor[1] + 1;
        }
        return changes;
    }

    /** The old tokens replaced by the new tokens {@code from} to {@code to}. */
    private static Change change(String oldText, List<Token> removed, String newText, List<Token> n, int from, int to) {
        int start;
        int end;
        if (from < to) {
            start = n.get(from).start();
            end = n.get(to - 1).end();
        } else {
            // Nothing added: the removed words are shown in front of the next new word, or after the last one.
            start = from < n.size() ? n.get(from).start() : from > 0 ? n.get(from - 1).end() : newText.length();
            end = start;
        }
        if (removed.isEmpty()) {
            return new Change(start, end, "", "", "");
        }
        int removedStart = removed.getFirst().start();
        int removedEnd = removed.getLast().end();
        boolean spaceBefore = removedStart > 0 && Character.isWhitespace(oldText.charAt(removedStart - 1))
                && start > 0 && !Character.isWhitespace(newText.charAt(start - 1));
        boolean spaceAfter = end > start || removedEnd < oldText.length()
                && Character.isWhitespace(oldText.charAt(removedEnd))
                && start < newText.length() && !Character.isWhitespace(newText.charAt(start));
        return new Change(start, end, oldText.substring(removedStart, removedEnd),
                spaceBefore ? " " : "", spaceAfter ? " " : "");
    }

    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(new Token(m.start(), m.end(), m.group()));
        }
        return tokens;
    }

    /** The longest common subsequence of the tokens, as pairs of the indexes of the equal ones. */
    private static List<int[]> lcs(List<Token> a, List<Token> b) {
        int[][] length = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                length[i][j] = a.get(i).text().equals(b.get(j).text()) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).text().equals(b.get(j).text())) {
                result.add(new int[]{i++, j++});
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }
}
