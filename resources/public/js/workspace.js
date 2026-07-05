// Drag-to-resize for the workspace split. The editor pane's width is a CSS
// custom property on .workspace-panes; dragging the divider updates it. While
// dragging we disable iframe pointer events (via the .dragging class) so the
// mousemove keeps firing on the document instead of being swallowed by the
// iframes underneath the cursor.
(function () {
  var panes = document.querySelector(".workspace-panes");
  var divider = panes && panes.querySelector(".workspace-divider");
  if (!panes || !divider) return;

  var dragging = false;

  divider.addEventListener("mousedown", function (e) {
    dragging = true;
    panes.classList.add("dragging");
    e.preventDefault();
  });

  document.addEventListener("mousemove", function (e) {
    if (!dragging) return;
    var rect = panes.getBoundingClientRect();
    var pct = ((e.clientX - rect.left) / rect.width) * 100;
    pct = Math.max(15, Math.min(85, pct));
    panes.style.setProperty("--editor-width", pct + "%");
  });

  document.addEventListener("mouseup", function () {
    if (!dragging) return;
    dragging = false;
    panes.classList.remove("dragging");
  });
})();

// Editor pane: code-server loads, then Calva connects to the REPL a few
// seconds later — until then the editor is up but "open" eval does nothing.
// The notebook JVM writes a readiness marker the moment Calva connects (its
// autoEvaluateCode.onConnect eval, served by Caddy at /repl-ready — see
// resources/sprite/setup.sh). Poll that marker through the owner output proxy and
// keep the "starting" overlay up until it appears, so the editor is revealed
// only once it's actually usable. Click-to-dismiss and a hard cap remain so a
// missed signal can never strand the overlay.
(function () {
  var overlay = document.querySelector("[data-editor-loading]");
  var workspace = document.querySelector(".workspace");
  var id = workspace && workspace.dataset.notebookId;
  if (!overlay || !id) return;

  var hidden = false;
  var poll;
  function hide() {
    if (hidden) return;
    hidden = true;
    if (poll) clearInterval(poll);
    overlay.classList.add("fading");
    setTimeout(function () { overlay.hidden = true; }, 300);
  }

  poll = setInterval(function () {
    fetch("/n/" + id + "/view/repl-ready", { cache: "no-store" })
      .then(function (r) { if (r.ok) hide(); })
      .catch(function () {});
  }, 1000);
  overlay.addEventListener("click", hide);
  setTimeout(hide, 45000);
})();

// Idle suspend: let a notebook's sprite go to sleep when nobody's using it.
// Sprites bill compute only while awake and suspend ~30s after their last
// inbound connection — but the editor and Clay live-reload WebSockets in these
// iframes keep streaming for as long as the page is open, so a left-open tab
// would otherwise pin the sprite awake (and billing) indefinitely. We tear the
// iframes down — closing those sockets, so the sprite suspends — when the tab
// is backgrounded for a while, or when it's visible but untouched. The
// visible-idle case gets a prompt + grace countdown first, so we never cut off
// someone who's reading or thinking; a backgrounded tab pauses on its own and
// auto-resumes when you come back. Restoring the iframes wakes the sprite
// (sub-second resume, JVM/REPL state preserved) and reloads the panes.
(function () {
  var workspace = document.querySelector(".workspace");
  var output = document.querySelector(".workspace-output iframe");
  if (!workspace || !output) return;
  var editor = document.querySelector(".workspace-editor iframe");

  // --- Tunables. Lengthen IDLE_MS if people get prompted while still
  //     thinking; shorten HIDDEN_GRACE_MS to reclaim backgrounded tabs
  //     sooner. PROMPT_GRACE_MS is the countdown shown before a visible-idle
  //     notebook pauses. Watch these fire in the devtools console: every
  //     transition logs a "[notebook] …" line. ---
  var IDLE_MS = 3 * 60 * 1000;          // visible + untouched this long -> prompt
  var PROMPT_GRACE_MS = 60 * 1000;      // prompt unanswered this long -> pause
  var HIDDEN_GRACE_MS = 2 * 60 * 1000;  // tab hidden this long -> pause

  function log(msg) { try { console.info("[notebook] " + msg); } catch (e) {} }

  var panes = [output, editor].filter(Boolean);
  var srcs = panes.map(function (f) { return f.src; });

  var state = "active"; // active | prompting | paused
  var lastActivity = Date.now();
  var promptTimer = null, hiddenTimer = null, countdownTimer = null;

  var veil = document.createElement("div");
  veil.className = "workspace-veil";
  veil.hidden = true;
  veil.innerHTML =
    '<div class="status-card">' +
    '<p class="veil-title"></p>' +
    '<p class="muted veil-body"></p>' +
    '<button type="button" class="button--primary veil-action"></button>' +
    "</div>";
  workspace.appendChild(veil);
  var veilTitle = veil.querySelector(".veil-title");
  var veilBody = veil.querySelector(".veil-body");
  var veilAction = veil.querySelector(".veil-action");

  function showVeil(title, body, label, onAction) {
    veilTitle.textContent = title;
    veilBody.textContent = body;
    veilAction.textContent = label;
    veilAction.onclick = onAction;
    veil.hidden = false;
  }
  function hideVeil() { veil.hidden = true; veilAction.onclick = null; }

  function clearPrompt() {
    if (promptTimer) { clearTimeout(promptTimer); promptTimer = null; }
    if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
  }

  function goActive() {
    clearPrompt();
    state = "active";
    hideVeil();
    lastActivity = Date.now();
  }

  function promptBody(secs) {
    return "This notebook will pause to free its sprite in " + secs +
      "s. It resumes right where you left off.";
  }

  function onIdle() {
    state = "prompting";
    log("idle " + Math.round(IDLE_MS / 1000) + "s -> prompting (pauses in " +
      Math.round(PROMPT_GRACE_MS / 1000) + "s unless you act)");
    var remaining = Math.round(PROMPT_GRACE_MS / 1000);
    showVeil("Still working?", promptBody(remaining), "Keep working", goActive);
    countdownTimer = setInterval(function () {
      remaining -= 1;
      if (remaining > 0) veilBody.textContent = promptBody(remaining);
    }, 1000);
    promptTimer = setTimeout(pause, PROMPT_GRACE_MS);
  }

  function pause() {
    clearPrompt();
    if (hiddenTimer) { clearTimeout(hiddenTimer); hiddenTimer = null; }
    state = "paused";
    log("paused — dropping iframes so the sprite can suspend");
    panes.forEach(function (f) { f.src = "about:blank"; });
    showVeil(
      "Notebook paused",
      "Paused while idle so its sprite can suspend and stop billing. " +
        "Resume to pick up where you left off.",
      "Resume", resume);
  }

  function resume() {
    log("resuming — reloading panes (sprite wakes on first request)");
    hideVeil();
    panes.forEach(function (f, i) { f.src = srcs[i]; });
    state = "active";
    lastActivity = Date.now();
  }

  // Any real interaction keeps the notebook alive (and dismisses the prompt).
  // The panes are same-origin, so we can also watch for activity *inside* the
  // editor and output iframes — otherwise typing in the editor would look idle.
  function bump() {
    lastActivity = Date.now();
    if (state === "prompting") goActive();
  }
  var ACTIVITY = ["keydown", "pointerdown", "mousemove", "wheel", "touchstart", "focus"];
  function watch(win) {
    try {
      ACTIVITY.forEach(function (evt) {
        win.addEventListener(evt, bump, { passive: true, capture: true });
      });
    } catch (e) { /* cross-origin or torn-down frame: skip */ }
  }
  watch(window);
  panes.forEach(function (f) {
    function attach() { try { if (f.contentWindow) watch(f.contentWindow); } catch (e) {} }
    f.addEventListener("load", attach);
    attach();
  });

  // One ticker drives the visible-idle check, so per-event work stays trivial.
  setInterval(function () {
    if (state !== "active" || document.hidden) return;
    if (Date.now() - lastActivity >= IDLE_MS) onIdle();
  }, 15000);

  document.addEventListener("visibilitychange", function () {
    if (document.hidden) {
      if (state === "active" && !hiddenTimer) {
        log("tab hidden — pausing in " + Math.round(HIDDEN_GRACE_MS / 1000) + "s if still away");
        hiddenTimer = setTimeout(pause, HIDDEN_GRACE_MS);
      }
    } else {
      if (hiddenTimer) { clearTimeout(hiddenTimer); hiddenTimer = null; log("tab back — pause cancelled"); }
      if (state === "paused") resume();   // came back -> wake it up
      else lastActivity = Date.now();
    }
  });
})();
