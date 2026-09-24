# Pebble

Pebble is a tiny local shared-state server.

It keeps one EDN value in memory, persists it to `.pebble-state.edn`, and
exposes it two ways:

- a browser UI at `/`
- an agent-facing socket and HTTP command endpoint

The shape is deliberately small: an atom, EDN command maps, a log file, and a
plain static UI.

## Start

From this directory:

```bash
PEBBLE_HTTP_PORT=7780 PEBBLE_SOCKET_PORT=7781 bb run
```

Then open:

```text
http://localhost:7780
```

If you do not set ports, Pebble defaults to:

- HTTP: `7777`
- socket: `7778`

## Agent Commands

Use the local helper script to talk to the socket:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :state}'
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :get :path [:some :path]}'
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :assoc :path [:some :path] :value "hello"}'
```

Commands are EDN maps, one per line. Useful ops include:

- `:state` - return the whole state
- `:get` - read a path
- `:assoc` - write a path
- `:dissoc` - remove a key from a map at a path
- `:rename-key` - rename a key in a map at a path
- `:reset` - replace the whole state
- `:undo` / `:redo` - walk the edit log
- `:eval` - evaluate Clojure source in the `pebble` namespace
- `:command` - echo the parsed command without changing state

Example eval:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :eval :code "(+ 1 2)"}'
```

## HTTP

Pebble also serves a few simple HTTP routes:

- `/` - browser UI
- `/state` - current state as EDN
- `/command` - POST an EDN command map
- `/whoami` - machine-readable description of this local service

Quick probes:

```bash
curl http://localhost:7780/whoami
curl http://localhost:7780/state
curl -X POST --data '{:op :ping}' http://localhost:7780/command
```

## Files

- `pebble.clj` - server, state, command handling, HTTP routes
- `toothpick.clj` - small helper macros
- `agent` - socket client for agents
- `public/` - browser UI
- `.pebble-state.edn` - persisted state
- `.pebble-log.edn` - append-only command log for undo/redo
- `values.md` - design notes
- `AGENTS.md` - operational notes for agents
