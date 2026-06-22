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

// Restart: bounce the notebook environment when Clay/the REPL stops
// responding. POSTs the restart (the server restarts the notebook AND
// code-server services and returns promptly), then polls /n/:id/counter —
// Clay's lightweight liveness endpoint, which 502s while dead and 200s once
// it's serving — until it's back, and reloads both panes. The editor pane
// reloads too because code-server was restarted (to reconnect Calva to the
// fresh REPL).
(function () {
  var workspace = document.querySelector(".workspace");
  var button = document.querySelector(".workspace-restart");
  var output = document.querySelector(".workspace-output iframe");
  var editor = document.querySelector(".workspace-editor iframe");
  var id = workspace && workspace.dataset.notebookId;
  if (!workspace || !button || !output || !id) return;

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function waitForClay(timeoutMs, onTick) {
    var start = Date.now();
    var deadline = start + timeoutMs;
    return (function poll() {
      if (onTick) onTick(Math.round((Date.now() - start) / 1000));
      return fetch("/n/" + id + "/counter", { cache: "no-store" })
        .then(function (r) { return r.ok; }, function () { return false; })
        .then(function (up) {
          if (up) return true;
          if (Date.now() >= deadline) return false;
          return sleep(2000).then(poll);
        });
    })();
  }

  button.addEventListener("click", function () {
    if (button.disabled) return;
    if (!window.confirm(
      "Restart the notebook environment? Your saved work is safe, but the " +
      "running session (REPL state, terminal) resets and the editor reloads. " +
      "This takes ~30 seconds.")) {
      return;
    }
    var label = button.textContent;
    button.disabled = true;
    button.textContent = "Restarting…";

    fetch("/notebooks/" + id + "/restart", { method: "POST" })
      .then(function (r) {
        if (!r.ok) throw new Error("restart request failed");
        return waitForClay(90000, function (secs) {
          button.textContent = "Waking up… " + secs + "s";
        });
      })
      .then(function () {
        // Whether or not the poll confirmed readiness, reload both panes:
        // if Clay isn't up yet it shows the "waking up" page and the user
        // can reload, rather than us spinning forever. The editor reloads
        // too because code-server was restarted (to reconnect Calva).
        output.src = output.src;
        if (editor) editor.src = editor.src;
      })
      .catch(function () {
        window.alert("Could not restart the notebook environment. Please try again in a moment.");
      })
      .then(function () {
        button.textContent = label;
        button.disabled = false;
      });
  });
})();

// Editor pane: code-server loads, then assembles the workspace (opens the
// notebook, the terminal, connects the REPL) over a few more seconds, and
// signals none of it. Keep a "starting" overlay up so the wait is obvious;
// hide it shortly after the iframe loads, on click, or at a hard cap.
(function () {
  var overlay = document.querySelector("[data-editor-loading]");
  var editor = document.querySelector(".workspace-editor iframe");
  if (!overlay || !editor) return;

  var hidden = false;
  function hide() {
    if (hidden) return;
    hidden = true;
    overlay.classList.add("fading");
    setTimeout(function () { overlay.hidden = true; }, 300);
  }

  editor.addEventListener("load", function () { setTimeout(hide, 3000); });
  overlay.addEventListener("click", hide);
  setTimeout(hide, 20000);
})();
