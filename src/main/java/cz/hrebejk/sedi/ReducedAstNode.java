/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.Tree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A reduced AST node representing a compilation unit, type, or member, containing
 * a pointer to the original tree, a map of MessageDigests per {@link ShaCategory}, and child nodes.
 */
public class ReducedAstNode {

    private final Tree originalTree;
    private final String name;
    private final NodeType nodeType;
    private final Map<ShaCategory, MessageDigest> messageDigests;
    private final List<ReducedAstNode> children;

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
    public ReducedAstNode(Tree originalTree, String name, NodeType nodeType) {
        this.originalTree = originalTree;
        this.name = name;
        this.nodeType = nodeType;
        this.messageDigests = new EnumMap<>(ShaCategory.class);
        for (ShaCategory category : ShaCategory.values()) {
            try {
                this.messageDigests.put(category, MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm not available", e);
            }
        }
        this.children = new ArrayList<>();
    }

    /**
     * Adds a child node.
     *
     * @param child child ReducedAstNode
     */
    public void addChild(ReducedAstNode child) {
        this.children.add(child);
    }

    /**
     * Updates the message digest of the specified category with an attribute string.
     *
     * @param category      the category
     * @param attribute     attribute string
     */
    public void updateDigest(ShaCategory category, String attribute) {
        if (attribute != null) {
            MessageDigest md = messageDigests.get(category);
            synchronized (md) {
                md.update(attribute.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Computes and returns the hex SHA string for the digest of the specified category.
     *
     * @param category the category
     * @return hex SHA string
     */
    public String getSha(ShaCategory category) {
        MessageDigest md = messageDigests.get(category);
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
    public Map<ShaCategory, MessageDigest> getMessageDigests() {
        return messageDigests;
    }

    /**
     * Returns child nodes.
     *
     * @return list of child ReducedAstNodes
     */
    public List<ReducedAstNode> getChildren() {
        return children;
    }
}
