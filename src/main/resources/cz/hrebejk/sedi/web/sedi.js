/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
import { h, toChildArray } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { register } from "/fleck/fleck.js";

/**
 * A part of the report which collapses to its first line, like a file to its title: a click on the line toggles
 * it. The state is kept by the browser; the key the server gives the fold keeps it when the report changes.
 */
function Fold(props) {
    // Open at first unless the server says it starts collapsed.
    const [open, setOpen] = useState(props["data-open"] !== "false");
    const [head, ...body] = toChildArray(props.children);
    const name = props["data-name"];
    const toggle = () => {
        // A click ending the selection of some text is no toggle.
        if (!window.getSelection().isCollapsed) {
            return;
        }
        setOpen(!open);
    };
    return h("div", { class: `${props.class} ${open ? "open" : "closed"}`, "data-name": name,
            "data-kind": props["data-kind"], "data-javadoc": props["data-javadoc"] },
        h("div", { class: "fold-head", title: `${open ? "Collapse" : "Expand"} ${name}`, onClick: toggle }, head),
        open ? body : null);
}

register("sedi-fold", Fold);

/**
 * Unchanged lines of a body hidden behind the note of their number, the first child: a click on the note shows the
 * lines instead of it, a click on the number of one of the lines hides them again.
 */
function Elided(props) {
    const [open, setOpen] = useState(false);
    const [note, ...lines] = toChildArray(props.children);
    if (!open) {
        return h("div", { class: `${props.class} closed`, title: "Show the lines", onClick: () => setOpen(true) },
            note);
    }
    const collapse = event => {
        if (event.target.closest(".ln") && window.getSelection().isCollapsed) {
            setOpen(false);
        }
    };
    return h("div", { class: `${props.class} open`, onClick: collapse }, lines);
}

register("sedi-elided", Elided);

/** How far below the tool bar the line of the spy reaches, its border included, as in sedi.css. */
const SPY_LINE = 25;

/**
 * A part of the line of the spy: the text of the nodes with their syntax colors, the spaces between the words
 * collapsed.
 *
 * @return the text and its markup
 */
function part(nodes) {
    const copy = document.createElement("span");
    nodes.forEach(node => copy.appendChild(node.cloneNode(true)));
    // What was there before, the tag of the level and the marks of the changes are left out, the new text kept.
    copy.querySelectorAll(".strike, .tag").forEach(e => e.remove());
    copy.querySelectorAll(".added, .renamed").forEach(e => e.replaceWith(...e.childNodes));
    copy.querySelectorAll("[title]").forEach(e => e.removeAttribute("title"));
    const walker = document.createTreeWalker(copy, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walker.nextNode()) {
        walker.currentNode.textContent = walker.currentNode.textContent.replace(/\s+/g, " ");
        texts.push(walker.currentNode);
    }
    // No spaces around it, nor the opening brace of a body at its end.
    for (const text of texts) {
        text.textContent = text.textContent.trimStart();
        if (text.textContent !== "") {
            break;
        }
    }
    let brace = true;
    for (const text of texts.reverse()) {
        let trimmed = text.textContent.trimEnd();
        if (brace && trimmed.endsWith("{")) {
            trimmed = trimmed.slice(0, -1).trimEnd();
            brace = false;
        }
        text.textContent = trimmed;
        if (trimmed !== "") {
            break;
        }
    }
    return { text: copy.textContent, html: copy.innerHTML };
}

/**
 * The signature on the first line of the fold of an element, as it is now: the struck through text, the tag of
 * the level and the opening brace left out.
 */
function signature(fold) {
    const code = fold.querySelector(":scope > .fold-head .code");
    return code ? part(Array.from(code.childNodes)) : { text: fold.dataset.name, html: null };
}

/** The name the element of the fold declares, or its signature if it declares none shown as such. */
function declared(fold) {
    const name = fold.querySelector(":scope > .fold-head .code .declaration");
    return name ? part([name]) : signature(fold);
}

/**
 * The folds the line at the height is in, the outermost first: the fold of the file, of the level and of the
 * elements. Found by where they are on the page, from the report down.
 *
 * @param y where the line is, from the top of the window
 */
function foldsAt(y) {
    const folds = [];
    let container = document.querySelector(".report");
    while (container) {
        const hit = Array.from(container.children).find(child => {
            const box = child.getBoundingClientRect();
            return box.top <= y && y < box.bottom;
        });
        if (!hit || !hit.classList.contains("fold")) {
            break;
        }
        folds.push(hit);
        container = hit;
    }
    return folds;
}

/**
 * Where the report is scrolled to: the types and the member the line at the height is in, whose first lines are
 * above it, the outer ones by name, the innermost one by its signature; the path of the file if the line is in
 * none. An element without anything under its signature has no fold, so the element it is in stands for it.
 *
 * @param y where the line is, from the top of the window
 * @return the parts, each with the separator in front of it and its fold, none if the line is in no file
 */
function spied(y) {
    // A fold whose first line is still shown below the height needs no line of the bar to say where it is.
    const folds = foldsAt(y).filter(fold => {
        const head = fold.querySelector(":scope > .fold-head");
        return head && head.getBoundingClientRect().bottom <= y;
    });
    const elements = folds.filter(fold => fold.dataset.kind);
    if (elements.length === 0) {
        return folds.length > 0 ? [{ separator: "", text: folds[0].dataset.name, html: null, fold: folds[0] }] : [];
    }
    return elements.map((fold, i) => ({
        // Types are separated by a fat dot, a member from its type by a light one.
        separator: i === 0 ? "" : fold.dataset.kind === "type" ? " \u2022 " : " \u00b7 ",
        ...(i === elements.length - 1 ? signature(fold) : declared(fold)),
        fold
    }));
}

/** How long the mouse stays on an element in the line of the spy before its javadoc is shown, in milliseconds. */
const DOC_DELAY = 400;

/**
 * The second line of the tool bar, shown while the report is scrolled into a file: the element or the file the
 * line right under it is in, see {@link spied}. It lies over the report, so showing it does not move the report.
 * A click on it scrolls the report back to the first line of the innermost of them, right under the line.
 *
 * With the mouse a while on an element of the line its javadoc unfolds between the buttons and the line; on another
 * element the javadoc of that one is shown at once, the javadoc not lower than before. It folds away when the mouse
 * leaves the line and the javadoc, or is on an element without one.
 */
function Spy() {
    const [parts, setParts] = useState([]);
    // The javadoc shown, or the last one shown while it folds away; the least height of its text in pixels.
    const [doc, setDoc] = useState({ fold: null, text: "", open: false, least: 0 });
    const timer = useRef(null);
    const self = useRef(null);
    const update = () => {
        const bar = self.current && self.current.closest(".toolbar");
        if (!bar) {
            return;
        }
        const next = spied(bar.getBoundingClientRect().bottom + SPY_LINE + 1);
        setParts(old => old.length === next.length && old.every((p, i) => p.fold === next[i].fold
            && p.html === next[i].html && p.text === next[i].text) ? old : next);
    };
    const scrollBack = () => {
        const head = parts.length > 0 && parts[parts.length - 1].fold.querySelector(":scope > .fold-head");
        if (head && window.getSelection().isCollapsed) {
            const bar = self.current.closest(".toolbar");
            // Right under the line, which then says what it is in: its top a pixel above where the line looks, so
            // the line looks into it, not into the element before it, however the scrolling rounds.
            window.scrollBy(0, head.getBoundingClientRect().top - (bar.getBoundingClientRect().bottom + SPY_LINE));
        }
    };
    const hide = () => {
        clearTimeout(timer.current);
        setDoc(old => old.open ? { ...old, open: false } : old);
    };
    const enter = part => {
        clearTimeout(timer.current);
        const text = part.fold.dataset.javadoc;
        if (!text) {
            hide();
        } else if (doc.open) {
            // Not lower than the javadoc shown before, so the line does not move up from under the mouse.
            const shown = self.current.querySelector(".spy-doc-text").offsetHeight;
            setDoc({ fold: part.fold, text, open: true, least: shown });
        } else {
            timer.current = setTimeout(() => setDoc({ fold: part.fold, text, open: true, least: 0 }), DOC_DELAY);
        }
    };
    // Also after the report is rendered again, which renders the bar too.
    useEffect(update);
    // The javadoc of an element no longer in the line, scrolled away from, goes away with it.
    useEffect(() => {
        if (doc.open && !parts.some(p => p.fold === doc.fold)) {
            hide();
        }
    }, [parts]);
    useEffect(() => {
        let pending = false;
        // At most once a frame; a fold collapsed or expanded moves the report after it is rendered.
        const later = () => {
            if (!pending) {
                pending = true;
                requestAnimationFrame(() => {
                    pending = false;
                    update();
                });
            }
        };
        window.addEventListener("scroll", later, { passive: true });
        window.addEventListener("resize", later);
        document.addEventListener("click", later);
        return () => {
            clearTimeout(timer.current);
            window.removeEventListener("scroll", later);
            window.removeEventListener("resize", later);
            document.removeEventListener("click", later);
        };
    }, []);
    return h("div", { class: `spy${parts.length === 0 ? " empty" : ""}`, ref: self, onMouseLeave: hide },
        h("div", { class: `spy-doc${doc.open ? " open" : ""}` },
            h("div", { class: "spy-doc-clip" },
                h("div", { class: "spy-doc-text", style: { minHeight: `${doc.least}px` } }, doc.text))),
        h("div", { class: "spy-line", onClick: scrollBack },
            parts.map(p => {
                const events = { onMouseEnter: () => enter(p), onMouseLeave: () => clearTimeout(timer.current) };
                return [p.separator ? h("span", { class: "spy-separator" }, p.separator) : null,
                    p.html === null ? h("span", { class: "spy-part", ...events }, p.text)
                        : h("span", { class: "spy-part", ...events, dangerouslySetInnerHTML: { __html: p.html } })];
            })));
}

register("sedi-spy", Spy);

// The keys of the console: a key presses the button of the tool bar with its data-key.
document.addEventListener("keydown", event => {
    if (event.ctrlKey || event.metaKey || event.altKey || event.target.closest("input, textarea, select")) {
        return;
    }
    const button = document.querySelector(`button[data-key="${CSS.escape(event.key)}"]`);
    if (button) {
        event.preventDefault();
        button.click();
    }
});
