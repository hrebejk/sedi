/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
// The keys of the console: a key presses the button of the tool bar with its data-key.
document.addEventListener("keydown", event => {
    if (event.ctrlKey || event.metaKey || event.altKey || event.target.closest("input, textarea, select")) {
        return;
    }
    const button = document.querySelector(`.toolbar button[data-key="${CSS.escape(event.key)}"]`);
    if (button) {
        event.preventDefault();
        button.click();
    }
});
