# Pebble Values

Pebble should be cute.

Cute software invites touch. It should feel small enough to pick up, inspect,
and change without bracing yourself. A good Pebble feature has the mood of a
useful desk object: plain purpose, friendly proportions, no hidden machinery
where a visible hinge would do.

Pebble should stay small.

Smallness is not a lack of ambition. It is the decision to keep the whole system
inside one person's head. Prefer one EDN command map over a protocol. Prefer a
function over a framework. Prefer a file that can be read top to bottom over a
directory that must be navigated by folklore.

Pebble should be comprehensible.

Every layer should have a good answer to "what is this?" The server is an atom,
a few commands, and two ways to talk to it. The UI is a tree, a selection, and
an editor. The agent interface is newline-delimited EDN. If an explanation needs
a diagram, make the diagram; if it needs a REPL expression, keep the expression
nearby.

Pebble should use macros carefully.

Macros are allowed to make the code more Pebble-like: denser, more local, more
direct. They are not allowed to become a private language that only yesterday's
author remembers. A good macro should remove ceremony while leaving ordinary
Clojure visible underneath. If a macro hides control flow, evaluation, or data
shape, it owes the reader a very good bargain.
