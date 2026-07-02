(ns hosted-clay.routes
  "Server-side paths in one place. Every internal link, form action, and
   redirect is built here by calling a function instead of hand-splicing
   `(str \"/n/\" id \"/...\")` at each call site — so a route change is a one-line
   edit here rather than a search across the UI and handlers, and an id can't
   drift into a typo'd path.

   The router in base-system.edn declares the same paths; these helpers are the
   inverse direction (params -> path) and have to be kept in step with it.

   A notebook lives under two prefixes:
     /n/:id    the owner's workspace page, its control actions, and the two live
               surfaces the page embeds — the Clay output at /n/:id/view/ and the
               editor (code-server) at /n/:id/edit/
     /s/:token the public read-only view, keyed on a separate unguessable share
               token so a share link never exposes the notebook id or its owner
               routes")

;; ---------- top-level pages ----------

(defn home      [] "/")
(defn login     [] "/login")
(defn logout    [] "/logout")
(defn dashboard [] "/dashboard")

;; ---------- owner notebook management: /n, /n/:id/* ----------

(defn notebooks
  "The notebook collection. A POST here creates the caller's notebook."
  []
  "/n")

(defn notebook
  "The owner's workspace page for notebook `id`."
  [id]
  (str "/n/" id))

(defn notebook-status  [id] (str (notebook id) "/status"))
(defn notebook-source  [id] (str (notebook id) "/source"))
(defn notebook-delete  [id] (str (notebook id) "/delete"))
(defn notebook-restart [id] (str (notebook id) "/restart"))
(defn notebook-retry   [id] (str (notebook id) "/retry"))
(defn notebook-suspend [id] (str (notebook id) "/suspend"))
(defn notebook-resume  [id] (str (notebook id) "/resume"))

(defn notebook-view
  "The live Clay output, proxied from the sprite and embedded as the workspace's
   output pane. Nested under the notebook so it doesn't collide with the control
   actions above; the trailing slash is canonical so Clay's relative asset URLs
   resolve under the prefix."
  [id]
  (str (notebook id) "/view/"))

;; ---------- owner editor: /n/:id/edit/* ----------

(defn editor-root
  "The code-server editor root, /n/<id>/edit/. Trailing slash canonical so its
   relative assets resolve under the prefix; /n/:id/edit (no slash) redirects
   here."
  [id]
  (str (notebook id) "/edit/"))

(defn editor
  "The editor with `?folder` pinned — code-server resolves the folder as
   query-param > CLI arg > last-opened, so it reliably opens the notebook folder
   (and Calva sees the project) regardless of any stale persisted state."
  [id]
  (str (editor-root id) "?folder=/home/sprite/notebook"))

;; ---------- public read-only share view: /s/:token/* ----------

(defn share
  "The public read-only view for share `token`. Trailing slash canonical, like
   the others; /s/:token (no slash) redirects here."
  [token]
  (str "/s/" token "/"))

;; ---------- absolute URLs ----------

(defn absolute
  "Join an absolute `base-url` (scheme + host, no trailing slash) with a path
   from one of the helpers above — for links that leave the app, e.g. a share
   link to copy or a notebook link in an email."
  [base-url path]
  (str base-url path))
