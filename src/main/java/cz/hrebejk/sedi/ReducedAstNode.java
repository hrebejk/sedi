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
import java.util.List;

/**
 * A reduced AST node representing a compilation unit, type, or member, containing
 * a pointer to the original tree, a list of MessageDigests (categories), and child nodes.
 */
public class ReducedAstNode {

    private final Tree originalTree;
    private final String name;
    private final NodeType nodeType;
    private final List<MessageDigest> messageDigests;
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
     * Creates a new ReducedAstNode with an initial MessageDigest category.
     *
     * @param originalTree pointer to the original javac AST tree node
     * @param name         name of the entity
     * @param nodeType     type of node
     */
    public ReducedAstNode(Tree originalTree, String name, NodeType nodeType) {
        this.originalTree = originalTree;
        this.name = name;
        this.nodeType = nodeType;
        this.messageDigests = new ArrayList<>();
        try {
            this.messageDigests.add(MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
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
     * Updates the message digest at the specified category index with an attribute string.
     *
     * @param categoryIndex index of the category digest
     * @param attribute     attribute string
     */
    public void updateDigest(int categoryIndex, String attribute) {
        if (attribute != null) {
            while (messageDigests.size() <= categoryIndex) {
                try {
                    messageDigests.add(MessageDigest.getInstance("SHA-256"));
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 algorithm not available", e);
                }
            }
            MessageDigest md = messageDigests.get(categoryIndex);
            synchronized (md) {
                md.update(attribute.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Computes and returns the hex SHA string for the digest at the specified category index.
     *
     * @param categoryIndex index of the category digest
     * @return hex SHA string
     */
    public String getSha(int categoryIndex) {
        if (categoryIndex < 0 || categoryIndex >= messageDigests.size()) {
            return "";
        }
        MessageDigest md = messageDigests.get(categoryIndex);
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
     * Returns the list of MessageDigests (categories).
     *
     * @return list of MessageDigests
     */
    public List<MessageDigest> getMessageDigests() {
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
