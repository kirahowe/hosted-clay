// Site-wide progressive enhancement. Each feature is a no-op unless its
// hook attributes are present, so this one file can load on every page:
//   [data-copy]          copy a value to the clipboard
//   [data-submit-label]  show progress on a form's submit button
//   [data-provision]     poll a provisioning notebook until it's ready
//
// Theming is OS-driven (prefers-color-scheme) and needs no JS — see
// tokens.css and the theme-color metas in layout/page.
(function () {
  "use strict";

  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      if (!navigator.clipboard) return;
      navigator.clipboard.writeText(btn.getAttribute("data-copy")).then(function () {
        var label = btn.textContent;
        btn.textContent = "Copied";
        btn.dataset.copied = "true";
        setTimeout(function () {
          btn.textContent = label;
          btn.removeAttribute("data-copied");
        }, 1500);
      }, function () {});
    });
  });

  // Disable the submit button and swap its label once a form is posting,
  // so a slow create/restart reads as "working", not "ignored". The form
  // still submits — disabling happens after the submit event fires.
  document.querySelectorAll("[data-submit-label]").forEach(function (form) {
    form.addEventListener("submit", function () {
      var btn = form.querySelector("button[type=submit], button:not([type])");
      if (btn) {
        btn.disabled = true;
        btn.textContent = form.getAttribute("data-submit-label");
      }
    });
  });

  // Provisioning: poll the notebook's status and reload when it leaves the
  // 'provisioning' state (ready -> the workspace; failed -> the error
  // page). The poll is deliberately gentle; provisioning takes minutes.
  var prov = document.querySelector("[data-provision]");
  if (prov) {
    var id = prov.getAttribute("data-provision");
    var poll = function () {
      fetch("/n/" + id + "/status", { cache: "no-store" })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          if (data && data.status !== "provisioning") { window.location.reload(); return; }
          setTimeout(poll, 2500);
        }, function () { setTimeout(poll, 2500); });
    };
    setTimeout(poll, 2500);
  }
})();
