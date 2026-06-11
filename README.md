# hosted-clay

A browser-based hosted environment for [Clay](https://scicloj.github.io/clay/)
notebooks: sign in, create a notebook, edit Clojure in the browser, see
Clay-rendered output, share a read-only link. Each notebook runs in its own
[Sprite](https://sprites.dev) (Fly.io's stateful sandbox).

## Run it locally

```
bb dev      # start the dev system on http://localhost:3000
bb repl     # nREPL with dev+test on the classpath; (dev) (go) (reset)
bb test     # kaocha
bb ci       # lint + fmt check + tests
```

Dev wants two env vars to exercise the full flow (both optional to boot):
`HANKO_API_URL` (a free Hanko Cloud project) and `SPRITES_TOKEN` (a Sprites
org token).

## Deploy

`bb deploy` (flyctl). One always-on Fly machine with a volume mounted at
`/data` for the SQLite file. Secrets: `SPRITES_TOKEN`, `HANKO_API_URL`,
`BASE_URL`, optionally `RESEND_API_KEY` + `EMAIL_FROM`.

## Docs

Architecture and decisions live in [docs/](docs/).

## License

MIT
