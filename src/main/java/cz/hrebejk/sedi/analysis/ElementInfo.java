/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reduced AST node representing a compilation unit, type, or member, containing
 * a pointer to the original tree, a map of MessageDigests per {@link ChangeCategory}, and child nodes.
 */
public class ElementInfo {

    private final Tree originalTree;
    private final String name;
    private final NodeType nodeType;
    private final Map<ChangeCategory, MessageDigest> messageDigests;
    private final List<ElementInfo> children;
    private final Map<Facet, MessageDigest> facetDigests;
    /** The attributes added to the digests of the facets, in the order added. */
    private final Map<Facet, List<String>> facetValues = new EnumMap<>(Facet.class);
    private int paramCount;
    private String elementId;
    /** The element javac resolved this node to, valid while its javac task is. */
    private Element element;
    private List<String> javadoc = List.of();
    /** The line of the first line of the javadoc, {@code 0} if not known. */
    private long javadocLine;
    /** The nodes of the body or of the signature being scanned, innermost last. */
    private final Deque<NodeInfo> openNodes = new ArrayDeque<>();
    private NodeInfo body;
    /** The nodes of the signature, by their trees (identity map). */
    private final Map<Tree, NodeInfo> signatureNodes = new IdentityHashMap<>();
    /** The outermost nodes of the signature, like the modifiers, the type and the parameters, in the order scanned. */
    private final List<NodeInfo> signatureRoots = new ArrayList<>();
    /** The names javac could not resolve, in the signature and the body (identity set). */
    private final Set<Tree> unresolved = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The source the element is in, {@code null} if it is not known. */
    private SourceText source;
    private long line;
    private boolean enumConstant;
    /** The qualified name of the old version of a type moved to another type or out of one, else {@code null}. */
    private String movedFrom;
    /** The qualified name of a moved type, see {@link #movedFrom}. */
    private String movedTo;

    /**
     * Enum defining the type of reduced AST node.
     */
    public enum NodeType {
        COMPILATION_UNIT,
        TYPE,
        METHOD,
        FIELD
    }

    /**
     * Creates a new ReducedAstNode with a MessageDigest for every category.
     *
     * @param originalTree pointer to the original javac AST tree node
     * @param name         name of the entity
     * @param nodeType     type of node
     */
    ElementInfo(Tree originalTree, String name, NodeType nodeType) {
        this.originalTree = originalTree;
        this.name = name;
        this.nodeType = nodeType;
        this.messageDigests = new EnumMap<>(ChangeCategory.class);
        for (ChangeCategory category : ChangeCategory.values()) {
            this.messageDigests.put(category, newDigest());
        }
        this.facetDigests = new EnumMap<>(Facet.class);
        for (Facet facet : Facet.values()) {
            this.facetDigests.put(facet, newDigest());
        }
        this.children = new ArrayList<>();
    }

    /**
     * Whether the field is the one javac makes of a component of the record. A record cannot declare instance fields
     * of its own.
     */
    public static boolean isRecordComponent(ClassTree owner, VariableTree field) {
        return owner.getKind() == Tree.Kind.RECORD && !field.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Updates the digest of the specified signature facet with an attribute string.
     *
     * @param facet     the facet
     * @param attribute attribute string
     */
    void updateFacet(Facet facet, String attribute) {
        if (attribute != null) {
            MessageDigest md = facetDigests.get(facet);
            synchronized (md) {
                md.update(attribute.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                facetValues.computeIfAbsent(facet, f -> new ArrayList<>()).add(attribute);
            }
        }
    }

    /**
     * Returns the attributes the hash of the facet was computed from, like the names of the thrown types.
     *
     * @param facet the facet
     * @return the attributes in the order added, empty if there are none
     */
    List<String> getFacetValues(Facet facet) {
        return facetValues.getOrDefault(facet, List.of());
    }

    /**
     * Computes and returns the hex SHA string for the specified facet.
     *
     * @param facet the facet
     * @return hex SHA string
     */
    public String getFacetSha(Facet facet) {
        return sha(facetDigests.get(facet));
    }

    /**
     * Returns the number of parameters of a method; zero for other elements.
     *
     * @return parameter count
     */
    public int getParamCount() {
        return paramCount;
    }

    /**
     * Sets the number of parameters of a method.
     *
     * @param paramCount parameter count
     */
    void setParamCount(int paramCount) {
        this.paramCount = paramCount;
    }

    /**
     * Returns the lines of the javadoc of the element, without the comment delimiters, the leading stars and
     * the indentation common to all lines.
     *
     * @return the lines, empty if the element has no javadoc
     */
    public List<String> getJavadoc() {
        return javadoc;
    }

    /**
     * Sets the javadoc of the element from the text javac returns for it.
     *
     * @param comment the comment text, {@code null} if there is none
     */
    void setJavadoc(String comment) {
        this.javadoc = javadocLines(comment);
    }

    /**
     * Returns the line in the source of the first line of the {@link #getJavadoc() javadoc}; the other lines follow
     * it one by one.
     *
     * @return the line, starting at 1; {@code 0} if there is no javadoc or its line is not known
     */
    public long getJavadocLine() {
        return javadoc.isEmpty() ? 0 : javadocLine;
    }

    /** Sets the line in the source of the first line of the javadoc. */
    void setJavadocLine(long javadocLine) {
        this.javadocLine = javadocLine;
    }

    /** Splits the comment into lines, drops the blank lines at its ends and the common indentation. */
    static List<String> javadocLines(String comment) {
        if (comment == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(comment.lines().map(String::stripTrailing).toList());
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int indent = lines.stream().filter(l -> !l.isEmpty())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        return lines.stream().map(l -> l.isEmpty() ? l : l.substring(indent)).toList();
    }

    /**
     * Returns the identity of the element javac resolved this node to.
     *
     * @return the id, {@code null} if the element could not be resolved
     */
    String getElementId() {
        return elementId;
    }

    /**
     * Returns the element javac resolved this node to; valid only while the analyzer which made it is open.
     *
     * @return the element, {@code null} if it could not be resolved
     */
    Element getElement() {
        return element;
    }

    /**
     * Sets the element javac resolved this node to, and its {@link #getElementId() id}.
     *
     * @param element the element, may be {@code null}
     */
    void setElement(Element element) {
        this.element = element;
        this.elementId = ElementIds.of(element);
    }

    /**
     * Adds a child node.
     *
     * @param child child ReducedAstNode
     */
    void addChild(ElementInfo child) {
        this.children.add(child);
    }

    /**
     * Updates the message digest of the specified category with an attribute string.
     *
     * @param category      the category
     * @param attribute     attribute string
     */
    void updateDigest(ChangeCategory category, String attribute) {
        if (attribute != null) {
            MessageDigest md = messageDigests.get(category);
            synchronized (md) {
                md.update(attribute.getBytes(StandardCharsets.UTF_8));
            }
            for (NodeInfo node : openNodes) {
                if (node.getCategory() == category) {
                    node.update(attribute, node == openNodes.getLast());
                }
                if (category == ChangeCategory.FULL) {
                    node.updateFull(attribute, node == openNodes.getLast());
                }
            }
        }
    }

    /**
     * Starts a node; everything hashed into the category until it is closed goes into it.
     *
     * @param path     the path to the node
     * @param category {@link ChangeCategory#BODY} for a node of the body, {@link ChangeCategory#SIGNATURE} for one of
     *                 the signature
     */
    void openNode(TreePath path, ChangeCategory category) {
        openNodes.addLast(new NodeInfo(path, category));
    }

    /** Completes the innermost open node and attaches it to its parent, or makes it the body. */
    void closeNode() {
        NodeInfo node = openNodes.removeLast();
        if (node.getCategory() == ChangeCategory.SIGNATURE) {
            signatureNodes.put(node.getTree(), node);
            if (openNodes.isEmpty()) {
                signatureRoots.add(node);
            }
        }
        if (!openNodes.isEmpty()) {
            openNodes.getLast().addChild(node);
        } else if (node.getCategory() == ChangeCategory.BODY) {
            body = node;
        }
    }

    /**
     * Returns the hash of a part of the signature of the element, such as the type of a parameter or an annotation:
     * references to renamed elements are hashed with their old names, so the old and the new version of a part
     * which changed only by such renames have the same hash.
     *
     * @param tree a tree of the signature of the element
     * @return the hash, {@code null} if the tree is not a part of the signature, e.g. one javac added
     */
    public String getSignatureSha(Tree tree) {
        NodeInfo node = signatureNodes.get(tree);
        return node == null ? null : node.getSha();
    }

    /**
     * Returns the {@link ChangeCategory#FULL} hash of a part of the signature of the element, of everything as it is
     * written: a part whose {@link #getSignatureSha signature hash} is equal to that of its old version, but not this
     * one, changed cosmetically, as a renamed type it refers to or a name qualified differently.
     *
     * @param tree a tree of the signature of the element
     * @return the hash, {@code null} if the tree is not a part of the signature
     */
    public String getSignatureFullSha(Tree tree) {
        NodeInfo node = signatureNodes.get(tree);
        return node == null ? null : node.getFullSha();
    }

    /**
     * Returns the {@link ChangeCategory#FULL} hash of what a part of the signature has besides the parts in it, like
     * the name of a parameter besides its type.
     *
     * @param tree a tree of the signature of the element
     * @return the hash, {@code null} if the tree is not a part of the signature
     */
    public String getSignatureFullOwnSha(Tree tree) {
        NodeInfo node = signatureNodes.get(tree);
        return node == null ? null : node.getFullOwnSha();
    }

    /**
     * The outermost nodes of the signature: the modifiers, the types, the parameters and so on, in the order scanned.
     *
     * @return the nodes
     */
    List<NodeInfo> getSignatureRoots() {
        return signatureRoots;
    }

    /**
     * Returns whether a tree of the element is a name javac could not resolve, as in code which does not compile.
     *
     * @param tree a tree of the signature or of the body of the element
     * @return {@code true} for an unresolved name
     */
    public boolean isUnresolved(Tree tree) {
        return unresolved.contains(tree);
    }

    /**
     * Returns the line of the declaration of the element in its source, where its signature starts after the
     * annotations.
     *
     * @return the line, starting at 1; {@code 0} if it is not known
     */
    public long getLine() {
        return line;
    }

    /**
     * Returns the line in the source where a tree of the element, such as an annotation, starts.
     *
     * @param tree a tree of the element
     * @return the line, starting at 1; {@code 0} if it is not known
     */
    public long getLine(Tree tree) {
        return source == null ? 0 : source.line(tree);
    }

    /**
     * Returns the text of a tree of the element as it is in the source, on a single line: comments left out and
     * every run of whitespace a single space.
     *
     * @param tree a tree of the element
     * @return the text, {@code null} if the tree has no place in the source, like the trees javac adds
     */
    public String getSource(Tree tree) {
        return source == null ? null : source.oneLine(tree);
    }

    /**
     * Returns the whole element as it is in the source: its annotations, modifiers, signature and body, with the
     * comments and line breaks, but without its javadoc. The first line is indented by spaces as in the source.
     *
     * @return the text, {@code null} if it is not known
     */
    public String getSourceText() {
        return source == null ? null : source.text(originalTree);
    }

    /**
     * Returns the text of a tree of the element like {@link #getSource(Tree)}, in runs of the same syntax role.
     *
     * @param tree a tree of the element
     * @return the runs, {@code null} if the tree has no place in the source
     */
    public List<SyntaxRoles.Run> getSourceRuns(Tree tree) {
        return source == null ? null : source.oneLineRuns(tree);
    }

    /**
     * Returns the whole element like {@link #getSourceText()}, in runs of the same syntax role.
     *
     * @return the runs, {@code null} if the source is not known
     */
    public List<SyntaxRoles.Run> getSourceTextRuns() {
        return source == null ? null : source.textRuns(originalTree);
    }

    /**
     * Returns the line where the element starts in the source, with its annotations, unlike {@link #getLine()}.
     *
     * @return the line, starting at 1; {@code 0} if it is not known
     */
    public long getStartLine() {
        return getLine(originalTree);
    }

    /** Sets the source the element is in, and the line of its declaration. */
    void setSource(SourceText source, long line) {
        this.source = source;
        this.line = line;
    }

    /** The names javac could not resolve (identity set). */
    Set<Tree> getUnresolved() {
        return unresolved;
    }

    /**
     * Returns the hashed nodes of the method body or of the field initializer.
     *
     * @return the root node, {@code null} if the element has no body
     */
    NodeInfo getBody() {
        return body;
    }

    /**
     * Computes and returns the hex SHA string for the digest of the specified category.
     *
     * @param category the category
     * @return hex SHA string
     */
    public String getSha(ChangeCategory category) {
        return sha(messageDigests.get(category));
    }

    static String sha(MessageDigest md) {
        synchronized (md) {
            try {
                MessageDigest clone = (MessageDigest) md.clone();
                byte[] hash = clone.digest();
                return bytesToHex(hash);
            } catch (CloneNotSupportedException e) {
                byte[] hash = md.digest();
                return bytesToHex(hash);
            }
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Returns the original tree pointer.
     *
     * @return original Tree
     */
    public Tree getOriginalTree() {
        return originalTree;
    }

    /**
     * Returns the name of the entity.
     *
     * @return name string
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the node type.
     *
     * @return NodeType
     */
    public NodeType getNodeType() {
        return nodeType;
    }

    /**
     * Returns whether the element is a constant of an enum. Its body, if any, is the arguments and the class body
     * following its name, like {@code ("a")} in {@code A("a")}, not an initializer after {@code =}.
     *
     * @return {@code true} for an enum constant
     */
    public boolean isEnumConstant() {
        return enumConstant;
    }

    void setEnumConstant(boolean enumConstant) {
        this.enumConstant = enumConstant;
    }

    /**
     * Returns the qualified name of the old version of a type which moved into another type or out of one, like a
     * top level type which became a nested one.
     *
     * @return the old qualified name, {@code null} if the type did not move
     */
    public String getMovedFrom() {
        return movedFrom;
    }

    /**
     * Returns the qualified name of a type which moved, see {@link #getMovedFrom()}.
     *
     * @return the qualified name, {@code null} if the type did not move
     */
    public String getMovedTo() {
        return movedTo;
    }

    void setMoved(String from, String to) {
        this.movedFrom = from;
        this.movedTo = to;
    }

    /**
     * Returns the MessageDigests by category.
     *
     * @return map of MessageDigests
     */
    Map<ChangeCategory, MessageDigest> getMessageDigests() {
        return messageDigests;
    }

    /**
     * Returns child nodes.
     *
     * @return list of child ReducedAstNodes
     */
    public List<ElementInfo> getChildren() {
        return children;
    }
}
