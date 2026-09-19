// Fun experiments in toy data visibility. Not served by Pebble right now.

const $ = (id) => document.querySelector(id);

const raw = $("#raw");
const tree = $("#tree");
const pathView = $("#path");
const refreshButton = $("#refresh");
const autoRefresh = $("#auto-refresh");
const command = $("#command");
const runCommand = $("#run-command");
const commandResult = $("#command-result");

let selectedPath = [];
let lastPollAt = 0;
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
  if (x && x.type === "map") return `{${x.xs.map(([k, v]) => `${edn(k)} ${edn(v)}`).join(", ")}}`;
  if (Array.isArray(x)) return `[${x.map(edn).join(" ")}]`;
  if (typeof x === "string") return x.startsWith(":") ? x : JSON.stringify(x);
  if (x === null) return "nil";
  return String(x);
}

function pathEdn(path) {
  return `[${path.map(edn).join(" ")}]`;
}

function select(path) {
  selectedPath = path;
  pathView.textContent = pathEdn(path);
  tree.querySelectorAll(".selected").forEach(x => x.classList.remove("selected"));
  tree.querySelector(`[data-path="${CSS.escape(JSON.stringify(path))}"]`)?.classList.add("selected");
}

function node(x, path = []) {
  const row = document.createElement("div");
  row.className = "node";
  row.dataset.path = JSON.stringify(path);
  row.onclick = (e) => {
    e.stopPropagation();
    select(path);
  };

  if (x && x.type === "map") {
    const details = document.createElement("details");
    details.open = true;
    const summary = document.createElement("summary");
    summary.append(row);
    row.textContent = "{}";
    details.append(summary);
    const kids = document.createElement("div");
    kids.className = "children";
    x.xs.forEach(([k, v]) => kids.append(pair(k, v, path.concat([k]))));
    details.append(kids);
    return details;
  }

  if (Array.isArray(x)) {
    const details = document.createElement("details");
    details.open = true;
    const summary = document.createElement("summary");
    summary.append(row);
    row.textContent = "[]";
    details.append(summary);
    const kids = document.createElement("div");
    kids.className = "children";
    x.forEach((v, n) => kids.append(pair(n, v, path.concat([n]))));
    details.append(kids);
    return details;
  }

  row.textContent = edn(x);
  return row;
}

function pair(k, v, path) {
  const wrap = document.createElement("div");
  wrap.className = "pair";
  const key = document.createElement("button");
  key.type = "button";
  key.className = "key";
  key.textContent = edn(k);
  key.onclick = (e) => {
    e.stopPropagation();
    select(path);
  };
  wrap.append(key, node(v, path));
  return wrap;
}

async function render() {
  const pollAt = Date.now();
  const res = await fetch("/state");
  const changedAt = Number(res.headers.get("x-pebble-changed-at") || 0);
  const text = await res.text();

  if (changedAt <= lastPollAt) {
    lastPollAt = pollAt;
    return;
  }

  raw.textContent = text;
  tree.replaceChildren(node(parseEdn(text)));
  select(selectedPath);
  lastPollAt = pollAt;
}

async function sendCommand() {
  const res = await fetch("/command", {method: "POST", body: command.value});
  commandResult.textContent = await res.text();
  await render();
}

refreshButton.onclick = () => render().catch(e => raw.textContent = String(e));
runCommand.onclick = () => sendCommand().catch(e => commandResult.textContent = String(e));
autoRefresh.onchange = () => {
  clearInterval(timer);
  if (autoRefresh.checked) timer = setInterval(render, 1000);
};

render().catch(e => raw.textContent = String(e));
