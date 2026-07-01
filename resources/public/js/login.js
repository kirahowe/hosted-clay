// Sign-in island: register the <hanko-auth> web component against the
// project's Hanko API and hop to the dashboard once a session exists.
// The API URL rides in on a data attribute so this file stays fully
// static — no inline script, which is what lets the site ship a strict
// script-src 'self' CSP. The elements bundle is vendored (pinned +
// checksum-verified from npm, see resources/public/js/vendor/), so the
// auth page never executes code fetched live from a third-party CDN.
import { register } from "/static/js/vendor/hanko-elements-3.0.0.js";

const el = document.querySelector("hanko-auth");
const apiUrl = el && el.dataset.apiUrl;
if (apiUrl) {
  const { hanko } = await register(apiUrl);
  hanko.onSessionCreated(() => {
    document.location.href = "/dashboard";
  });
}
