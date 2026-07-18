const CALLBACK_SET_PERCENT = "SET_PERCENT";
const CALLBACK_SET_THRESHOLD = "SET_THRESHOLD";
const CALLBACK_SET_INTERVAL = "SET_INTERVAL";
const CALLBACK_SET_PRICE = "SET_PRICE";
const CALLBACK_SET_VOLUME = "SET_VOLUME";
const CALLBACK_RESET = "RESET";
const CALLBACK_UPDATE = "UPDATE";

const BUILD_ID = "debug-config-2026-07-18-1";
const DEFAULT_PERCENT = 50;
const DEFAULT_INTERVAL_MINUTES = 60;
const MAX_ROWS_PER_MESSAGE = 80;
const CHAT_PREFIX = "chat:";
const BINANCE_FUTURES_WS_BASE_URL = "wss://fstream.binance.com/market/ws/";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health" || url.pathname === "/healthz")) {
      return new Response("OK", { headers: { "content-type": "text/plain" } });
    }

    if (request.method === "GET" && url.pathname === "/debug/config") {
      return jsonResponse({
        buildId: BUILD_ID,
        hasBotToken: Boolean(env.BOT_TOKEN),
        botTokenLength: env.BOT_TOKEN ? env.BOT_TOKEN.length : 0,
        botTokenPreview: maskValue(env.BOT_TOKEN),
        hasWebhookSecret: Boolean(env.TELEGRAM_WEBHOOK_SECRET),
        webhookSecretLength: env.TELEGRAM_WEBHOOK_SECRET ? env.TELEGRAM_WEBHOOK_SECRET.length : 0,
        webhookSecretPreview: maskValue(env.TELEGRAM_WEBHOOK_SECRET),
        hasStrictWebhookSecret: env.STRICT_WEBHOOK_SECRET === "true",
        hasBotState: Boolean(env.BOT_STATE),
        botName: env.BOT_NAME || null,
        envKeys: Object.keys(env).sort()
      });
    }

    if (request.method === "GET" && url.pathname === "/debug/binance") {
      const forbidden = authorizeDebugRequest(url, env);
      if (forbidden) {
        return forbidden;
      }

      try {
        const state = defaultState();
        state.percent = 1;
        const moves = await findGainers(state);
        return jsonResponse({
          ok: true,
          count: moves.length,
          sample: moves.slice(0, 5)
        });
      } catch (error) {
        return jsonResponse({
          ok: false,
          message: error?.message || String(error),
          stack: error?.stack || null
        }, 500);
      }
    }

    if (request.method === "POST" && url.pathname === "/webhook") {
      if (env.STRICT_WEBHOOK_SECRET === "true" && env.TELEGRAM_WEBHOOK_SECRET) {
        const secret = request.headers.get("X-Telegram-Bot-Api-Secret-Token");
        if (secret !== env.TELEGRAM_WEBHOOK_SECRET) {
          return new Response("Forbidden", { status: 403 });
        }
      }

      const update = await request.json();
      ctx.waitUntil(handleUpdate(update, env));
      return new Response("OK");
    }

    return new Response("Not found", { status: 404 });
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(sendDueScheduledReports(env));
  }
};

async function handleUpdate(update, env) {
  if (update.callback_query) {
    await handleCallback(update.callback_query, env);
    return;
  }

  const message = update.message;
  if (!message?.text || !message.chat?.id) {
    return;
  }

  const chatId = String(message.chat.id);
  const text = message.text.trim();

  if (text.toLowerCase() === "/start") {
    const state = await getState(env, chatId);
    await saveState(env, chatId, {
      ...state,
      active: true,
      nextRunAt: Date.now() + state.intervalMinutes * 60_000
    });
    await sendMenu(env, chatId, "Binance Futures scanner is running.");
    return;
  }

  const state = await getState(env, chatId);
  const inputMode = state.inputMode;
  if (inputMode) {
    state.inputMode = null;
    await handleInput(env, chatId, state, inputMode, text);
    return;
  }

  if (text.toLowerCase() === "/update") {
    await sendCurrentGainers(env, chatId);
    return;
  }

  await sendMenu(env, chatId, "Choose an action.");
}

async function handleCallback(callback, env) {
  const chatId = String(callback.message.chat.id);
  const data = callback.data || "";
  await answerCallback(env, callback.id);

  const state = await getState(env, chatId);
  state.active = true;

  if (data === CALLBACK_SET_PERCENT || data === CALLBACK_SET_THRESHOLD) {
    state.inputMode = "PERCENT";
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Enter percent from 1 to 100. Example: 90");
    return;
  }

  if (data === CALLBACK_SET_INTERVAL) {
    state.inputMode = "INTERVAL";
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Enter interval in minutes. Example: 60");
    return;
  }

  if (data === CALLBACK_SET_PRICE) {
    state.inputMode = "PRICE_MIN";
    delete state.pendingMinPrice;
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Min");
    return;
  }

  if (data === CALLBACK_SET_VOLUME) {
    state.inputMode = "VOLUME_MIN";
    delete state.pendingMinVolume;
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Min (M)");
    return;
  }

  if (data === CALLBACK_RESET) {
    await resetSettings(env, chatId);
    return;
  }

  if (data === CALLBACK_UPDATE) {
    await sendCurrentGainers(env, chatId);
    return;
  }

  await sendMenu(env, chatId, "Choose an action.");
}

async function handleInput(env, chatId, state, inputMode, text) {
  if (inputMode === "PERCENT") {
    const percent = parseNumber(text.replace("%", ""));
    if (!Number.isFinite(percent) || percent < 1 || percent > 100) {
      state.inputMode = "PERCENT";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Percent must be from 1 to 100.");
      return;
    }

    state.percent = percent;
    state.nextRunAt = Date.now();
    await saveState(env, chatId, state);
    await sendMenu(env, chatId, `Percent set to ${formatPercent(percent)}.`);
    await sendCurrentGainers(env, chatId);
    return;
  }

  if (inputMode === "INTERVAL") {
    const intervalMinutes = Number.parseInt(text.trim(), 10);
    if (!Number.isFinite(intervalMinutes) || intervalMinutes < 1) {
      state.inputMode = "INTERVAL";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Interval must be at least 1 minute.");
      return;
    }

    state.intervalMinutes = intervalMinutes;
    state.nextRunAt = Date.now();
    await saveState(env, chatId, state);
    await sendMenu(env, chatId, `Interval set to ${intervalMinutes} minutes.`);
    await sendCurrentGainers(env, chatId);
    return;
  }

  if (inputMode === "PRICE_MIN") {
    const min = parseNumber(text);
    if (!Number.isFinite(min) || min < 0) {
      state.inputMode = "PRICE_MIN";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Min");
      return;
    }

    state.pendingMinPrice = min;
    state.inputMode = "PRICE_MAX";
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Max");
    return;
  }

  if (inputMode === "PRICE_MAX") {
    const min = state.pendingMinPrice;
    const max = parseNumber(text);
    if (!Number.isFinite(min)) {
      state.inputMode = "PRICE_MIN";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Min");
      return;
    }
    if (!Number.isFinite(max) || max < min) {
      state.inputMode = "PRICE_MAX";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Max");
      return;
    }

    state.minPrice = min;
    state.maxPrice = max;
    delete state.pendingMinPrice;
    state.nextRunAt = Date.now();
    await saveState(env, chatId, state);
    await sendMenu(env, chatId, `Price range set to ${formatPrice(min)} - ${formatPrice(max)}.`);
    await sendCurrentGainers(env, chatId);
    return;
  }

  if (inputMode === "VOLUME_MIN") {
    const min = parseNumber(text);
    if (!Number.isFinite(min) || min < 0) {
      state.inputMode = "VOLUME_MIN";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Min (M)");
      return;
    }

    state.pendingMinVolume = min;
    state.inputMode = "VOLUME_MAX";
    await saveState(env, chatId, state);
    await sendText(env, chatId, "Max (M)");
    return;
  }

  if (inputMode === "VOLUME_MAX") {
    const min = state.pendingMinVolume;
    const max = parseNumber(text);
    if (!Number.isFinite(min)) {
      state.inputMode = "VOLUME_MIN";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Min (M)");
      return;
    }
    if (!Number.isFinite(max) || max < min) {
      state.inputMode = "VOLUME_MAX";
      await saveState(env, chatId, state);
      await sendText(env, chatId, "Max (M)");
      return;
    }

    state.minVolumeMillions = min;
    state.maxVolumeMillions = max;
    delete state.pendingMinVolume;
    state.nextRunAt = Date.now();
    await saveState(env, chatId, state);
    await sendMenu(env, chatId, `Volume range set to ${formatVolumeMillions(min)} - ${formatVolumeMillions(max)}.`);
    await sendCurrentGainers(env, chatId);
  }
}

async function resetSettings(env, chatId) {
  const state = defaultState();
  state.active = true;
  state.nextRunAt = Date.now() + DEFAULT_INTERVAL_MINUTES * 60_000;
  await saveState(env, chatId, state);
  await sendMenu(env, chatId, "Settings reset to defaults.");
}

async function sendCurrentGainers(env, chatId) {
  try {
    const state = await getState(env, chatId);
    const gainers = await findGainers(state);
    await sendReport(env, chatId, state, gainers);
  } catch (error) {
    await sendText(env, chatId, `Update failed: ${friendlyError(error)}`);
  }
}

async function sendDueScheduledReports(env) {
  const now = Date.now();
  let cursor;

  do {
    const page = await env.BOT_STATE.list({ prefix: CHAT_PREFIX, cursor, limit: 100 });
    cursor = page.cursor;

    await Promise.all(page.keys.map(async ({ name }) => {
      const chatId = name.slice(CHAT_PREFIX.length);
      const state = await getState(env, chatId);
      if (!state.active || state.nextRunAt > now) {
        return;
      }

      state.nextRunAt = now + state.intervalMinutes * 60_000;
      await saveState(env, chatId, state);

      try {
        const gainers = await findGainers(state);
        await sendReport(env, chatId, state, gainers);
      } catch (error) {
        console.error(`Scheduled update failed for ${chatId}:`, error);
      }
    }));
  } while (cursor);
}

async function findGainers(state) {
  const [tickers, markPrices] = await Promise.all([
    readWebSocketJson(`${BINANCE_FUTURES_WS_BASE_URL}!ticker@arr`),
    readWebSocketJson(`${BINANCE_FUTURES_WS_BASE_URL}!markPrice@arr@1s`)
  ]);

  const fundingBySymbol = new Map();
  for (const item of Array.isArray(markPrices) ? markPrices : []) {
    const symbol = item.s || item.symbol || "";
    const funding = parseNumber(item.r ?? item.lastFundingRate) * 100;
    if (symbol.endsWith("USDT") && Number.isFinite(funding)) {
      fundingBySymbol.set(symbol, funding);
    }
  }

  const moves = [];
  for (const ticker of Array.isArray(tickers) ? tickers : []) {
    const symbol = ticker.s || ticker.symbol || "";
    if (!symbol.endsWith("USDT")) {
      continue;
    }

    const percent = parseNumber(ticker.P ?? ticker.priceChangePercent);
    if (!Number.isFinite(percent) || percent < state.percent) {
      continue;
    }

    const price = parseNumber(ticker.c ?? ticker.lastPrice);
    if (!Number.isFinite(price) || price < state.minPrice || price > state.maxPrice) {
      continue;
    }

    const volumeMillions = parseNumber(ticker.q ?? ticker.quoteVolume) / 1_000_000;
    if (!Number.isFinite(volumeMillions) || volumeMillions < state.minVolumeMillions || volumeMillions > state.maxVolumeMillions) {
      continue;
    }

    moves.push({
      symbol,
      price,
      fundingPercent: fundingBySymbol.get(symbol),
      priceChangePercent: percent,
      volumeMillions
    });
  }

  moves.sort((a, b) => b.priceChangePercent - a.priceChangePercent);
  return moves;
}

async function readWebSocketJson(url) {
  const response = await fetch(url, {
    headers: {
      Upgrade: "websocket"
    }
  });
  const socket = response.webSocket;
  if (!socket) {
    throw new Error(`WebSocket handshake failed for ${url}`);
  }

  socket.accept();
  const message = await new Promise((resolve, reject) => {
    const timeoutId = setTimeout(() => {
      try {
        socket.close(1000, "timeout");
      } catch (_error) {
        // Nothing else to do if the socket is already closed.
      }
      reject(new Error(`Timed out reading ${url}`));
    }, 25_000);

    socket.addEventListener("message", (event) => {
      clearTimeout(timeoutId);
      socket.close(1000, "done");
      resolve(event.data);
    }, { once: true });

    socket.addEventListener("error", () => {
      clearTimeout(timeoutId);
      reject(new Error(`WebSocket failed for ${url}`));
    }, { once: true });
  });

  return JSON.parse(message);
}

async function sendReport(env, chatId, state, moves) {
  if (moves.length <= MAX_ROWS_PER_MESSAGE) {
    await sendHtml(env, chatId, renderReport(state, moves), menuKeyboard());
    return;
  }

  const totalParts = Math.ceil(moves.length / MAX_ROWS_PER_MESSAGE);
  for (let part = 0; part < totalParts; part += 1) {
    const from = part * MAX_ROWS_PER_MESSAGE;
    const to = Math.min(from + MAX_ROWS_PER_MESSAGE, moves.length);
    const keyboard = part === totalParts - 1 ? menuKeyboard() : undefined;
    await sendHtml(env, chatId, renderReport(state, moves.slice(from, to)), keyboard);
  }
}

function renderReport(state, moves) {
  let message = "";
  message += `Percent: <code>${formatPercent(state.percent)}</code>\n`;
  message += `Min price: <code>${formatPrice(state.minPrice)}</code>\n`;
  message += `Max price: <code>${formatPriceOrAll(state.maxPrice)}</code>\n`;
  message += `Min volume: <code>${formatVolumeMillions(state.minVolumeMillions)}</code>\n`;
  message += `Max volume: <code>${formatVolumeOrAll(state.maxVolumeMillions)}</code>\n`;
  message += `Interval: <code>${state.intervalMinutes} minutes</code>\n\n`;

  if (moves.length === 0) {
    return `${message}<code>No Binance Futures coins are above the percent.</code>`;
  }

  message += "<pre><code>";
  message += fixedRow(["Coin", "Price", "Funding", "Percent", "Volume"], [12, 12, 9, 8, 10]);
  for (const move of moves) {
    message += fixedRow([
      move.symbol,
      formatPrice(move.price),
      formatFunding(move.fundingPercent),
      formatPercent(move.priceChangePercent),
      formatVolumeMillions(move.volumeMillions)
    ], [12, 12, 9, 8, 10]);
  }
  message += "</code></pre>";
  return message;
}

async function sendMenu(env, chatId, text) {
  const state = await getState(env, chatId);
  const message = `${text}\n\n`
    + `Percent: ${formatPercent(state.percent)}\n`
    + `Min price: ${formatPrice(state.minPrice)}\n`
    + `Max price: ${formatPriceOrAll(state.maxPrice)}\n`
    + `Min volume: ${formatVolumeMillions(state.minVolumeMillions)}\n`
    + `Max volume: ${formatVolumeOrAll(state.maxVolumeMillions)}\n`
    + `Interval: ${state.intervalMinutes} minutes`;

  await sendText(env, chatId, message, menuKeyboard());
}

function menuKeyboard() {
  return {
    inline_keyboard: [
      [{ text: "Percent", callback_data: CALLBACK_SET_PERCENT }],
      [{ text: "Price", callback_data: CALLBACK_SET_PRICE }],
      [{ text: "Volume", callback_data: CALLBACK_SET_VOLUME }],
      [{ text: "Interval", callback_data: CALLBACK_SET_INTERVAL }],
      [{ text: "Update", callback_data: CALLBACK_UPDATE }],
      [{ text: "Reset", callback_data: CALLBACK_RESET }]
    ]
  };
}

function authorizeDebugRequest(url, env) {
  const secret = url.searchParams.get("secret");
  const allowedSecrets = [
    env.DEBUG_SECRET,
    env.TELEGRAM_WEBHOOK_SECRET,
    env.BOT_TOKEN
  ].filter(Boolean);

  if (!secret || !allowedSecrets.includes(secret)) {
    return new Response("Forbidden", { status: 403 });
  }
  return null;
}

function maskValue(value) {
  if (!value) {
    return null;
  }
  if (value.length <= 8) {
    return "*".repeat(value.length);
  }
  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value, null, 2), {
    status,
    headers: {
      "content-type": "application/json"
    }
  });
}

async function answerCallback(env, callbackQueryId) {
  await telegram(env, "answerCallbackQuery", { callback_query_id: callbackQueryId });
}

async function sendText(env, chatId, text, replyMarkup) {
  await telegram(env, "sendMessage", {
    chat_id: chatId,
    text,
    reply_markup: replyMarkup,
    disable_web_page_preview: true
  });
}

async function sendHtml(env, chatId, text, replyMarkup) {
  await telegram(env, "sendMessage", {
    chat_id: chatId,
    text,
    parse_mode: "HTML",
    reply_markup: replyMarkup,
    disable_web_page_preview: true
  });
}

async function telegram(env, method, payload) {
  if (!env.BOT_TOKEN) {
    throw new Error("BOT_TOKEN secret is not configured.");
  }

  const response = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload)
  });

  const result = await response.json();
  if (!result.ok) {
    throw new Error(`Telegram ${method} failed: ${result.description || response.status}`);
  }
  return result.result;
}

async function getState(env, chatId) {
  const value = await env.BOT_STATE.get(`${CHAT_PREFIX}${chatId}`, "json");
  return normalizeState(value);
}

async function saveState(env, chatId, state) {
  await env.BOT_STATE.put(`${CHAT_PREFIX}${chatId}`, JSON.stringify(normalizeState(state)));
}

function defaultState() {
  return {
    active: false,
    percent: DEFAULT_PERCENT,
    intervalMinutes: DEFAULT_INTERVAL_MINUTES,
    minPrice: 0,
    maxPrice: Number.MAX_VALUE,
    minVolumeMillions: 0,
    maxVolumeMillions: Number.MAX_VALUE,
    nextRunAt: Date.now() + DEFAULT_INTERVAL_MINUTES * 60_000,
    inputMode: null
  };
}

function normalizeState(value) {
  const fallback = defaultState();
  const state = { ...fallback, ...(value || {}) };
  state.percent = finiteOrDefault(state.percent, fallback.percent);
  state.intervalMinutes = Math.max(1, Number.parseInt(state.intervalMinutes, 10) || fallback.intervalMinutes);
  state.minPrice = finiteOrDefault(state.minPrice, fallback.minPrice);
  state.maxPrice = finiteOrDefault(state.maxPrice, fallback.maxPrice);
  state.minVolumeMillions = finiteOrDefault(state.minVolumeMillions, fallback.minVolumeMillions);
  state.maxVolumeMillions = finiteOrDefault(state.maxVolumeMillions, fallback.maxVolumeMillions);
  state.nextRunAt = finiteOrDefault(state.nextRunAt, fallback.nextRunAt);
  return state;
}

function finiteOrDefault(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function parseNumber(value) {
  return Number.parseFloat(String(value || "").trim().replace(",", "."));
}

function fixedRow(values, widths) {
  return values.map((value, index) => String(value).padEnd(widths[index], " ")).join(" | ") + "\n";
}

function formatPercent(value) {
  return Number.isFinite(value) ? `${value.toFixed(2)}%` : "n/a";
}

function formatFunding(value) {
  return Number.isFinite(value) ? `${value.toFixed(4)}%` : "n/a";
}

function formatPrice(value) {
  if (!Number.isFinite(value)) {
    return "n/a";
  }
  if (value >= 1000) {
    return value.toFixed(2);
  }
  if (value >= 1) {
    return value.toFixed(4);
  }
  return value.toFixed(8);
}

function formatPriceOrAll(value) {
  return value === Number.MAX_VALUE ? "all" : formatPrice(value);
}

function formatVolumeMillions(value) {
  return Number.isFinite(value) ? `${value.toFixed(2)}M` : "n/a";
}

function formatVolumeOrAll(value) {
  return value === Number.MAX_VALUE ? "all" : formatVolumeMillions(value);
}

function friendlyError(error) {
  const message = error?.message || String(error);
  if (message.includes("WebSocket failed") || message.includes("Timed out reading") || message.includes("WebSocket handshake failed")) {
    return "Binance Futures WebSocket market data is unavailable.";
  }
  return message;
}
