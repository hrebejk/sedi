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
import java.util.ArrayList;
import java.util.List;

/**
 * A node of the body of a method or of a field initializer, with the {@link ChangeCategory#BODY} hash of its subtree:
 * renames undone and names of locals ignored, so two nodes with an equal hash differ at most cosmetically. A node of
 * a signature, such as the type of a parameter, has the {@link ChangeCategory#SIGNATURE} hash instead: references to
 * renamed elements undone.
 */
final class NodeInfo {

    private final TreePath path;
    private final ChangeCategory category;
    private final MessageDigest digest = ElementInfo.newDigest();
    private final MessageDigest ownDigest = ElementInfo.newDigest();
    private final List<NodeInfo> children = new ArrayList<>();
    private String sha;
    private String ownSha;

    /** @param category the category whose attributes go into the hash: {@code BODY} or {@code SIGNATURE} */
    NodeInfo(TreePath path, ChangeCategory category) {
        this.path = path;
        this.category = category;
    }

    /** The category whose attributes go into the hash. */
    ChangeCategory getCategory() {
        return category;
    }

    /**
     * Adds an attribute of the subtree.
     *
     * @param attribute the attribute
     * @param own       {@code true} if the attribute is of the node itself, not of a node inside it
     */
    void update(String attribute, boolean own) {
        byte[] bytes = attribute.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes);
        // The number and presence of the children are left to the diff of the children.
        if (own && !attribute.startsWith("LIST:") && !attribute.equals("NULL")) {
            ownDigest.update(bytes);
        }
    }

    void addChild(NodeInfo child) {
        children.add(child);
    }

    /** The path to the node; its leaf is the node. */
    TreePath getPath() {
        return path;
    }

    Tree getTree() {
        return path.getLeaf();
    }

    /** The hash of the subtree; the node must be complete. */
    String getSha() {
        if (sha == null) {
            sha = ElementInfo.sha(digest);
        }
        return sha;
    }

    /** The hash of what the node has besides its children, like the name of a member select or an operator. */
    String getOwnSha() {
        if (ownSha == null) {
            ownSha = ElementInfo.sha(ownDigest);
        }
        return ownSha;
    }

    /** The nodes directly in this one, in the order javac scans them. */
    List<NodeInfo> getChildren() {
        return children;
    }
}
