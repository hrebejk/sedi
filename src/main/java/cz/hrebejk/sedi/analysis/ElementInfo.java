/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private int paramCount;
    private String elementId;
    /** The nodes of the body being scanned, innermost last. */
    private final Deque<NodeInfo> openNodes = new ArrayDeque<>();
    private NodeInfo body;

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
            }
        }
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
     * Returns the identity of the element javac resolved this node to.
     *
     * @return the id, {@code null} if the element could not be resolved
     */
    String getElementId() {
        return elementId;
    }

    /**
     * Sets the identity of the element javac resolved this node to.
     *
     * @param elementId the id, may be {@code null}
     */
    void setElementId(String elementId) {
        this.elementId = elementId;
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
            if (category == ChangeCategory.BODY) {
                for (NodeInfo node : openNodes) {
                    node.update(attribute, node == openNodes.getLast());
                }
            }
        }
    }

    /**
     * Starts a node of the body; everything hashed into {@link ChangeCategory#BODY} until it is closed goes into it.
     *
     * @param path the path to the node
     */
    void openNode(TreePath path) {
        openNodes.addLast(new NodeInfo(path));
    }

    /** Completes the innermost open node and attaches it to its parent, or makes it the body. */
    void closeNode() {
        NodeInfo node = openNodes.removeLast();
        if (openNodes.isEmpty()) {
            body = node;
        } else {
            openNodes.getLast().addChild(node);
        }
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
