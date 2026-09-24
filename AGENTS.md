# Pebble Agent Notes

Pebble is a shared local state object. Treat it as the working surface for plans,
comments, and other structured context that should not drift upward in chat.

## Agent Access

Use the local helper from the repo root:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :state}'
```

Common commands:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :get :path [:some :path]}'
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :assoc :path [:some :path] :value "new value"}'
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :reset :value {}}'
```

Pebble commands are EDN maps. Prefer `pr-str`/EDN-shaped data over ad hoc
string formatting.

Use `:eval` to run arbitrary Clojure source text. Put the code in the `:code`
string; Pebble reads and evaluates the string in the `pebble` namespace and
returns the last form's value:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :eval :code "(+ 1 2)"}'
```

Use `{:op :command ...}` as a dry echo path when you want to see the parsed
command map without changing state:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :command :note "hello"}'
```

## Plans

When Daniel says to put a plan into Pebble, use an ordered vector of pairs:

```clojure
[["first step" ""]
 ["second step" ""]]
```

The first element is the step text. The second element is Daniel's comment slot.
Leave comment slots as empty strings unless he provides the text.

Pull the whole state before acting on a Pebble plan:

```bash
PEBBLE_SOCKET_PORT=7781 ./agent '{:op :state}'
```

Then execute the steps in order.

## Running Pebble

The dev server is usually run with:

```bash
PEBBLE_HTTP_PORT=7780 PEBBLE_SOCKET_PORT=7781 bb run
```

The browser UI is at `http://localhost:7780`. The HTTP page is for the user; the
socket/`./agent` path is the agent-facing interface.
