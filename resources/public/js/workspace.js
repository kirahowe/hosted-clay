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
