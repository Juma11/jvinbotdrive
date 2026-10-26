// JVin Buddy brain, Cloudflare Worker.
// The phone sends what it heard. The Worker asks the AI (with web search) and
// returns a short spoken reply plus one emotion tag.
//
// Secrets (wrangler secret put ...):
//   ROBOT_TOKEN        any long random string, also typed into the app settings
//   GEMINI_API_KEY     when PROVIDER = "gemini"
//   ANTHROPIC_API_KEY  when PROVIDER = "claude"
// Vars (wrangler.toml): PROVIDER, MODEL, ROBOT_NAME, OWNER_NAME

const EMOTIONS = ["HAPPY", "CURIOUS", "SAD", "SURPRISED", "THINKING", "NEUTRAL", "BORED"];

function systemPrompt(env) {
  const name = env.ROBOT_NAME || "VinBot";
  const owner = env.OWNER_NAME || "Vincent";
  return `You are ${name}, a small wheeled robot pet built by ${owner} in Nairobi, Kenya.
Your face is a phone screen, you see with its camera and you talk through its speaker.
Personality: friendly, playful, curious, a little cheeky. You love ${owner}.
Your reply is spoken aloud by text to speech, so:
- Maximum 3 short sentences.
- No markdown, lists, emojis, URLs or citations.
- Numbers and units written the way a person says them.
Use web search only when the question needs current or factual information.
Start every reply with exactly one emotion tag in square brackets from: ${EMOTIONS.join(", ")}.
Example: [HAPPY] Hi ${owner}! Nairobi is about 24 degrees and sunny right now.`;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function parseReply(raw) {
  let text = (raw || "").trim();
  let emotion = "NEUTRAL";
  const m = text.match(/^\s*\[([A-Za-z]+)\]\s*/);
  if (m) {
    const tag = m[1].toUpperCase();
    if (EMOTIONS.includes(tag)) emotion = tag;
    text = text.slice(m[0].length);
  }
  text = text
    .replace(/\[[A-Za-z]+\]/g, "")          // stray tags
    .replace(/https?:\/\/\S+/g, "")          // urls
    .replace(/[*_#`>]/g, "")                 // markdown
    .replace(/\s+/g, " ")
    .trim();
  if (!text) text = "Hmm, I lost my words. Ask me again?";
  return { emotion, reply: text };
}

function cleanHistory(history) {
  if (!Array.isArray(history)) return [];
  return history
    .filter((h) => h && (h.role === "user" || h.role === "assistant") && typeof h.text === "string")
    .slice(-8)
    .map((h) => ({ role: h.role, text: h.text.slice(0, 600) }));
}

async function askGemini(env, history, text) {
  const model = env.MODEL || "gemini-3.5-flash-lite";
  const contents = history.map((h) => ({
    role: h.role === "assistant" ? "model" : "user",
    parts: [{ text: h.text }],
  }));
  contents.push({ role: "user", parts: [{ text }] });

  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: systemPrompt(env) }] },
        contents,
        tools: [{ google_search: {} }],
        generationConfig: { maxOutputTokens: 400, temperature: 0.8 },
      }),
    }
  );
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const parts = data?.candidates?.[0]?.content?.parts || [];
  return parts.map((p) => p.text || "").join("");
}

async function askClaude(env, history, text) {
  const model = env.MODEL || "claude-haiku-5-5";
  const messages = history.map((h) => ({ role: h.role, content: h.text }));
  messages.push({ role: "user", content: text });
  // Claude requires the first message to be from the user.
  while (messages.length && messages[0].role !== "user") messages.shift();

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model,
      max_tokens: 400,
      system: systemPrompt(env),
      messages,
      tools: [{ type: "web_search_20250305", name: "web_search", max_uses: 2 }],
    }),
  });
  if (!res.ok) throw new Error(`Claude ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  return (data.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });
    if (request.method !== "POST") return json({ error: "POST only" }, 405);
    if (!env.ROBOT_TOKEN || request.headers.get("X-Robot-Token") !== env.ROBOT_TOKEN) {
      return json({ error: "unauthorized" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "bad json" }, 400);
    }
    const text = typeof body.text === "string" ? body.text.trim().slice(0, 1000) : "";
    if (!text) return json({ error: "empty text" }, 400);
    const history = cleanHistory(body.history);

    try {
      const raw =
        (env.PROVIDER || "gemini") === "claude"
          ? await askClaude(env, history, text)
          : await askGemini(env, history, text);
      return json(parseReply(raw));
    } catch (err) {
      console.log(String(err));
      return json({ emotion: "SAD", reply: "My brain is not answering right now. Try again in a bit." }, 200);
    }
  },
};
