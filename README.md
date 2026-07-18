# EvilHamster Cloudflare Worker

Telegram webhook bot for Binance Futures movers.

## Cloudflare setup

Create a KV namespace in Cloudflare, then add it to the Worker bindings:

```text
Variable name: BOT_STATE
Type: KV namespace
```

Do this in the Cloudflare dashboard under the Worker project settings. The binding must be named exactly `BOT_STATE`.

Add these Cloudflare Worker variables/secrets:

```text
BOT_TOKEN=8933244482:AAE-smYkAOKmH7YH7zMt51UEWrdqm48_VBQ
TELEGRAM_WEBHOOK_SECRET=any-long-random-string
```

`BOT_NAME` is already configured in `wrangler.toml`.

## Cloudflare Git deploy commands

Build command:

```bash
npm install
```

Deploy command:

```bash
npx wrangler deploy
```

## Telegram webhook

After the Worker is deployed, register the Telegram webhook:

```bash
BOT_TOKEN=8933244482:AAE-smYkAOKmH7YH7zMt51UEWrdqm48_VBQ TELEGRAM_WEBHOOK_SECRET=any-long-random-string npm run set-webhook -- https://your-worker.workers.dev
```

Use the same `TELEGRAM_WEBHOOK_SECRET` value that you configured in Cloudflare.

## Local checks

```bash
npm run check
npx wrangler deploy --dry-run
```
