const token = process.env.BOT_TOKEN;
const workerUrl = process.argv[2];
const secretToken = process.env.TELEGRAM_WEBHOOK_SECRET;

if (!token) {
  console.error("BOT_TOKEN is required.");
  process.exit(1);
}

if (!workerUrl) {
  console.error("Usage: BOT_TOKEN=... npm run set-webhook -- https://your-worker.workers.dev");
  process.exit(1);
}

const url = new URL(`https://api.telegram.org/bot${token}/setWebhook`);
url.searchParams.set("url", `${workerUrl.replace(/\/$/, "")}/webhook`);
url.searchParams.set("drop_pending_updates", "true");
url.searchParams.set("allowed_updates", JSON.stringify(["message", "callback_query"]));
if (secretToken) {
  url.searchParams.set("secret_token", secretToken);
}

const response = await fetch(url, { method: "POST" });
const payload = await response.json();
console.log(JSON.stringify(payload, null, 2));

if (!payload.ok) {
  process.exit(1);
}
