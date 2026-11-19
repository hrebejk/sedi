/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
import { h, toChildArray } from "preact";
import { useState } from "preact/hooks";
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
    return h("div", { class: `${props.class} ${open ? "open" : "closed"}`, "data-name": name },
        h("div", { class: "fold-head", title: `${open ? "Collapse" : "Expand"} ${name}`, onClick: toggle }, head),
        open ? body : null);
}

register("sedi-fold", Fold);

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
