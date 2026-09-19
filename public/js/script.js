const refreshButton = document.querySelector("#refresh");
const autoRefresh = document.querySelector("#auto-refresh");
const tree = document.querySelector("#tree");
const selected = document.querySelector("#selected");
const valueEditor = document.querySelector("#value-editor");
const assocKeyField = document.querySelector("#assoc-key-field");
const assocKey = document.querySelector("#assoc-key");
const setValue = document.querySelector("#set-value");
const editResult = document.querySelector("#edit-result");

let latestChangedAt = 0;
let latestParsedState = null;
let selection = {path: [], part: "value"};
let timer;

function parseEdn(source) {
  let i = 0;
  const peek = () => source[i];
  const next = () => source[i++];
  const ws = () => {
    while (/\s|,/.test(peek() || "")) i++;
  };

  function string() {
    let s = "";
    next();
    while (i < source.length) {
      const c = next();
      if (c === "\"") return s;
      if (c === "\\") {
        const e = next();
        s += ({n: "\n", r: "\r", t: "\t", "\"": "\"", "\\": "\\"})[e] ?? e;
      } else {
        s += c;
      }
    }
    throw Error("unterminated string");
  }

  function token() {
    let s = "";
    while (i < source.length && !/[\s,\[\]{}]/.test(peek())) s += next();
    if (s === "nil") return null;
    if (s === "true") return true;
    if (s === "false") return false;
    if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
    return s;
  }

  function value() {
    ws();
    const c = peek();
    if (c === "\"") return string();
    if (c === "[") return vector();
    if (c === "{") return map();
    return token();
  }

  function vector() {
    const xs = [];
    next();
    ws();
    while (peek() !== "]") {
      xs.push(value());
      ws();
    }
    next();
    return xs;
  }

  function map() {
    const xs = [];
    next();
    ws();
    while (peek() !== "}") {
      const key = value();
      ws();
      xs.push([key, value()]);
      ws();
    }
    next();
    return {type: "map", xs};
  }

  const x = value();
  ws();
  if (i < source.length) throw Error(`extra input near ${source.slice(i, i + 20)}`);
  return x;
}

function edn(x) {
  if (x && x.type === "map") return "{...}";
  if (Array.isArray(x)) return "[...]";
  return scalarEdn(x);
}

function fullEdn(x) {
  if (x && x.type === "map") return `{${x.xs.map(([k, v]) => `${fullEdn(k)} ${fullEdn(v)}`).join(", ")}}`;
  if (Array.isArray(x)) return `[${x.map(fullEdn).join(" ")}]`;
  return scalarEdn(x);
}

function scalarEdn(x) {
  if (typeof x === "string") return x.startsWith(":") ? x : JSON.stringify(x);
  if (x === null) return "nil";
  return String(x);
}

function samePath(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function pathKey(path) {
  return JSON.stringify(path);
}

function sameSelection(a, b) {
  return a.part === b.part && samePath(a.path, b.path);
}

function selectionKey(x) {
  return `${x.part}:${pathKey(x.path)}`;
}

function rowSelector(x) {
  return `[data-selection="${CSS.escape(selectionKey(x))}"]`;
}

function at(path, part = "value") {
  return {path, part};
}

function select(next, focus = false) {
  selection = next;
  tree.querySelectorAll(".selected").forEach(x => x.classList.remove("selected"));
  const rows = tree.querySelectorAll(rowSelector(next));
  rows.forEach(x => x.classList.add("selected"));
  selected.textContent = `${fullEdn(next.path)} ${next.part}`;
  setValue.disabled = !["key", "value"].includes(next.part);
  if (next.part === "key") valueEditor.value = fullEdn(selectedKey());
  if (focus) rows[0]?.focus();
}

function row(label, target) {
  const x = document.createElement("button");
  x.type = "button";
  x.className = "row";
  x.dataset.selection = selectionKey(target);
  x.dataset.path = pathKey(target.path);
  x.dataset.part = target.part;
  x.textContent = label;
  if (sameSelection(target, selection)) x.classList.add("selected");
  x.onclick = (e) => {
    e.stopPropagation();
    select(target);
  };
  return x;
}

function node(x, path = []) {
  if (x && x.type === "map") {
    return branch("{}", path, x.xs.map(([k, v]) => [k, v, path.concat([k])]));
  }

  if (Array.isArray(x)) {
    return branch("[]", path, x.map((v, i) => [i, v, path.concat([i])]));
  }

  return row(edn(x), at(path));
}

function branch(label, path, entries) {
  const details = document.createElement("details");
  const summary = document.createElement("summary");
  const children = document.createElement("div");

  details.open = true;
  children.className = "children";
  summary.append(row(label, at(path)));
  entries.forEach(([k, v, childPath]) => children.append(pair(k, v, childPath)));
  details.append(summary, children);
  return details;
}

function pair(k, v, path) {
  const x = document.createElement("div");
  x.className = "pair";
  x.append(row(edn(k), at(path, "key")), node(v, path));
  return x;
}

function visible(row) {
  for (let x = row.parentElement; x && x !== tree; x = x.parentElement) {
    if (x.tagName === "DETAILS" && !x.open && !x.querySelector("summary")?.contains(row)) {
      return false;
    }
  }
  return true;
}

function rowSelection(row) {
  return at(JSON.parse(row.dataset.path), row.dataset.part);
}

function visibleSelections() {
  return [...tree.querySelectorAll(".row")]
    .filter(visible)
    .map(rowSelection);
}

function selectedIndex(xs) {
  return xs.findIndex(x => sameSelection(x, selection));
}

function detailsFor(path) {
  return [...tree.querySelectorAll(rowSelector(at(path)))]
    .map(x => x.closest("summary")?.parentElement)
    .find(x => x?.tagName === "DETAILS");
}

function parentPath(path) {
  return path.length ? path.slice(0, -1) : path;
}

function selectedKey() {
  return selection.path[selection.path.length - 1];
}

function selectedParentPath() {
  return selection.path.slice(0, -1);
}

function valueAt(path, x = latestParsedState) {
  return path.reduce((y, k) => {
    if (y && y.type === "map") return y.xs.find(([key]) => samePath([key], [k]))?.[1];
    if (Array.isArray(y)) return y[k];
  }, x);
}

function focusEditorWith(text, mode) {
  setMode(mode);
  valueEditor.value = text;
  valueEditor.focus();
  valueEditor.select();
}

function editSelectedValue() {
  const value = valueAt(selection.path);
  if (typeof value === "string" && !value.startsWith(":")) {
    focusEditorWith(value, "text");
  } else {
    focusEditorWith(fullEdn(value), "edn");
  }
}

function editSelectedKey() {
  focusEditorWith(fullEdn(selectedKey()), "edn");
}

function move(delta) {
  const xs = visibleSelections();
  if (!xs.length) return;
  const here = selectedIndex(xs);
  const n = Math.max(0, Math.min(xs.length - 1, here + delta));
  select(xs[n], true);
}

function moveRight() {
  if (selection.part === "key") {
    select(at(selection.path), true);
    return;
  }

  const details = detailsFor(selection.path);
  if (!details) return;
  if (!details.open) {
    details.open = true;
    return;
  }

  const xs = visibleSelections();
  const n = selectedIndex(xs);
  const child = xs.slice(n + 1).find(x =>
    x.part === "key" &&
    x.path.length > selection.path.length &&
    samePath(x.path.slice(0, selection.path.length), selection.path));
  if (child) select(child, true);
}

function moveLeft() {
  const details = detailsFor(selection.path);
  if (details?.open) {
    details.open = false;
    return;
  }
  if (selection.part === "value" && selection.path.length) {
    select(at(selection.path, "key"), true);
    return;
  }
  select(at(parentPath(selection.path)), true);
}

function navigate(e) {
  if (e.target.closest("input, textarea")) return;
  if (e.key === "e") {
    e.preventDefault();
    if (selection.part === "value") editSelectedValue();
    else valueEditor.focus();
    return;
  }
  if (e.key === "k") {
    e.preventDefault();
    if (selection.part === "value" && selection.path.length) editSelectedKey();
    return;
  }
  if (e.key === "m") {
    e.preventDefault();
    toggleMode();
    return;
  }
  if (e.key === "a") {
    e.preventDefault();
    showAssocKey();
    assocKey.focus();
    assocKey.select();
    return;
  }
  if (e.key === "r") {
    e.preventDefault();
    select(at([]), true);
    return;
  }
  if (!["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(e.key)) return;
  e.preventDefault();
  ({
    ArrowUp: () => move(-1),
    ArrowDown: () => move(1),
    ArrowRight: moveRight,
    ArrowLeft: moveLeft,
  })[e.key]();
}

function toggleMode() {
  const next = document.querySelector("input[name='edit-mode']:checked").value === "text"
    ? "edn"
    : "text";
  document.querySelector(`input[name='edit-mode'][value='${next}']`).checked = true;
}

function setMode(mode) {
  document.querySelector(`input[name='edit-mode'][value='${mode}']`).checked = true;
}

function showAssocKey() {
  assocKeyField.hidden = false;
}

function syncAssocKey() {
  assocKeyField.hidden = document.activeElement !== assocKey && !assocKey.value;
}

async function poll() {
  const res = await fetch("/state");
  const changedAt = Number(res.headers.get("x-pebble-changed-at") || 0);
  const text = await res.text();

  if (changedAt > latestChangedAt) {
    latestChangedAt = changedAt;
    latestParsedState = parseEdn(text);
    tree.replaceChildren(node(latestParsedState));
    select(selection);
    console.log("pebble", {changedAt, state: text});
  }
}

async function setSelectedValue() {
  if (selection.part === "key") {
    await renameSelectedKey();
    return;
  }
  if (selection.part !== "value") return;
  const value = editorValue();
  const key = assocKey.value.trim();
  const path = key ? selection.path.concat([parseEdn(key)]) : selection.path;
  const command = path.length
    ? `{:op :assoc :path ${fullEdn(path)} :value ${value}}`
    : `{:op :reset :value ${value}}`;
  await sendCommand(command);
  if (key) {
    assocKey.value = "";
    syncAssocKey();
  }
}

async function renameSelectedKey() {
  const newKey = parseEdn(valueEditor.value);
  const path = selectedParentPath();
  const command = `{:op :rename-key :path ${fullEdn(path)} :old-key ${fullEdn(selectedKey())} :new-key ${fullEdn(newKey)}}`;
  await sendCommand(command, false);
  selection = at(path.concat([newKey]), "key");
  latestChangedAt = 0;
  await poll();
}

function editorValue() {
  const mode = document.querySelector("input[name='edit-mode']:checked").value;
  return mode === "text" ? JSON.stringify(valueEditor.value) : valueEditor.value;
}

async function sendCommand(command, refresh = true) {
  const res = await fetch("/command", {method: "POST", body: command});
  editResult.textContent = await res.text();
  if (!refresh) return;
  latestChangedAt = 0;
  await poll();
}

refreshButton.onclick = () => poll().catch(console.error);
setValue.onclick = () => setSelectedValue().catch(e => editResult.textContent = String(e));
valueEditor.onfocus = () => {
  if (selection.part === "key") setMode("edn");
};
document.addEventListener("keydown", navigate);
assocKey.onfocus = showAssocKey;
assocKey.oninput = syncAssocKey;
assocKey.onblur = syncAssocKey;

function syncAutoRefresh() {
  clearInterval(timer);
  if (autoRefresh.checked) timer = setInterval(() => poll().catch(console.error), 1000);
}

autoRefresh.onchange = syncAutoRefresh;

syncAutoRefresh();
syncAssocKey();
poll().catch(console.error);
