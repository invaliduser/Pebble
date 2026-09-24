# Pebble

Pebble is a tiny local shared-state server.  It is intended for human-agent collaboration.  The human looks at the FE, or just *refers* to it; the agent talks to Pebble over a Unix socket.

"Throw that data into Pebble under `:todos`" is something you might say.

It keeps one EDN value in memory, persists it to `.pebble-state.edn`, and
exposes it two ways:

- a browser UI at `/`
- an agent-facing socket and HTTP command endpoint

You can start it with `bb run` from within the directory.  You will need [Babashka](https://github.com/babashka/babashka) installed.
