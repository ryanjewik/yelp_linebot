import os
import json
from datetime import datetime

import requests
from flask import Flask, request, abort
from dotenv import load_dotenv
from typing import List

from linebot import LineBotApi, WebhookHandler
from linebot.exceptions import InvalidSignatureError
from linebot.models import MessageEvent, TextMessage, TextSendMessage

# Load environment variables
load_dotenv()

CHANNEL_SECRET = os.getenv("LINE_CHANNEL_SECRET")
CHANNEL_ACCESS_TOKEN = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
YELP_API_KEY = os.getenv("YELP_API_KEY")

if CHANNEL_SECRET is None or CHANNEL_ACCESS_TOKEN is None:
    raise ValueError("LINE_CHANNEL_SECRET and LINE_CHANNEL_ACCESS_TOKEN must be set")

app = Flask(__name__)

line_bot_api = LineBotApi(CHANNEL_ACCESS_TOKEN)
handler = WebhookHandler(CHANNEL_SECRET)

LOG_FILE = "events.log"
YELP_LOG_FILE = "yelp.log"


def log_event_pretty(event):
    """Pretty-print event JSON and save it to events.log."""
    data = event.as_json_dict()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    pretty = json.dumps(data, indent=2, ensure_ascii=False)

    print("\n===== Incoming Event =====")
    print(pretty)
    print("==========================\n")

    # Append to log file
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(f"\n===== {timestamp} =====\n")
        f.write(pretty)
        f.write("\n===========================\n")

def chunk_text(text: str, max_len: int = 3500) -> List[str]:
    """
    Split text into chunks <= max_len, trying to cut on newlines.
    Used so a single business JSON can become multiple LINE messages.
    """
    chunks = []
    s = text

    while s:
        if len(s) <= max_len:
            chunks.append(s)
            break

        # Try to break at a newline near max_len
        split_pos = s.rfind("\n", 0, max_len)
        if split_pos == -1:
            split_pos = max_len

        chunks.append(s[:split_pos])
        s = s[split_pos:]

    return chunks


def call_yelp_chat(query: str) -> List[str]:
    """
    Call Yelp AI Chat API with the given query and:
      - Log full raw JSON to yelp.log (untruncated)
      - Return a list of LINE message strings, focusing on the first business
    """
    if not YELP_API_KEY:
        return [
            "Yelp API key is not configured.\n\n"
            "Please set YELP_API_KEY in your .env file."
        ]

    url = "https://api.yelp.com/ai/chat/v2"
    headers = {
        "accept": "application/json",
        "content-type": "application/json",
        "authorization": f"Bearer {YELP_API_KEY}",
    }

    req_body = {"query": query}

    # Optional: user_context from env
    locale = os.getenv("YELP_LOCALE")
    lat = os.getenv("YELP_LATITUDE")
    lon = os.getenv("YELP_LONGITUDE")

    user_context = {}
    if locale:
        user_context["locale"] = locale
    if lat and lon:
        try:
            user_context["latitude"] = float(lat)
            user_context["longitude"] = float(lon)
        except ValueError:
            pass

    if user_context:
        req_body["user_context"] = user_context

    try:
        resp = requests.post(url, headers=headers, json=req_body, timeout=10)
    except Exception as e:
        return [f"Error calling Yelp API: {e}"]

    if not resp.ok:
        text = resp.text
        if len(text) > 2000:
            text = text[:2000] + "\n...(truncated)..."
        return [f"Yelp API error {resp.status_code}:\n{text}"]

    # Parse JSON and log full contents
    try:
        data = resp.json()
    except json.JSONDecodeError:
        text = resp.text
        if len(text) > 4000:
            text = text[:4000] + "\n\n...(truncated)..."
        return [text]

    # 1) Log full raw JSON to yelp.log (no truncation)
    from datetime import datetime
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    full_pretty = json.dumps(data, indent=2, ensure_ascii=False)

    with open(YELP_LOG_FILE, "a", encoding="utf-8") as f:
        f.write(f"\n===== {timestamp} =====\n")
        f.write(full_pretty)
        f.write("\n===========================\n")

    # 2) Build LINE reply parts

    messages: List[str] = []

    # Base natural-language text from Yelp
    base_text = data.get("response", {}).get("text") or "Yelp returned a response."
    messages.append(
        base_text
        + "\n\n(Full raw Yelp JSON logged to yelp.log. Showing first business details below.)"
    )

    # Extract first business (for now, we focus on that to avoid truncation)
    first_business = None
    entities = data.get("entities", [])
    for entity in entities:
        if "businesses" in entity and entity["businesses"]:
            first_business = entity["businesses"][0]
            break

    if first_business is not None:
        biz_json = json.dumps(first_business, indent=2, ensure_ascii=False)
        biz_chunks = chunk_text(biz_json, max_len=3500)

        # Prepend a label to the first chunk
        if biz_chunks:
            biz_chunks[0] = "First business (full JSON):\n\n" + biz_chunks[0]

        # LINE can only send up to 5 messages per reply, so cap it
        # 1 (base_text) + up to 4 chunks of this business
        messages.extend(biz_chunks[:4])
    else:
        # No business found → fallback: short truncated view of whole JSON
        short_pretty = full_pretty
        if len(short_pretty) > 3500:
            short_pretty = short_pretty[:3500] + "\n\n...(truncated JSON preview)..."
        messages.append("No businesses found in entities.\n\n" + short_pretty)

    return messages




@app.route("/callback", methods=["POST"])
def callback():
    # get X-Line-Signature header value
    signature = request.headers.get("X-Line-Signature")

    # get request body as text
    body = request.get_data(as_text=True)

    # handle webhook body
    try:
        handler.handle(body, signature)
    except InvalidSignatureError:
        print("Invalid signature. Check channel secret/access token.")
        abort(400)

    return "OK"


# Event handler for text messages
@handler.add(MessageEvent, message=TextMessage)
def handle_message(event: MessageEvent):
    # Log the full LINE event to file + console
    log_event_pretty(event)

    # raw_text keeps original casing; we use lowercased copy for command checks
    raw_text = event.message.text.strip()
    text_lower = raw_text.lower()

    # --- /yelp command ---
    # Usage: /yelp what's a good sushi place in LA?
    if raw_text.startswith("/yelp"):
        prompt = raw_text[len("/yelp"):].strip()
        if not prompt:
            reply = ["Usage: /yelp <your question>\nExample: /yelp Best ramen near me"]
        else:
            reply = call_yelp_chat(prompt)  # now returns List[str]

        # Ensure reply is a list
        if isinstance(reply, str):
            reply = [reply]

        # Map to TextSendMessage; LINE allows up to 5 per reply
        messages = [TextSendMessage(text=part) for part in reply[:5]]

        line_bot_api.reply_message(
            event.reply_token,
            messages
        )
        return


    # --- regular commands (help, ping, echo) ---
    if text_lower == "help":
        reply = (
            "Commands:\n"
            "- help: show this help\n"
            "- ping: test latency\n"
            "- echo <text>: I'll repeat your text\n"
            "- /yelp <query>: ask Yelp AI (e.g. /yelp good vegan sushi in SF)"
        )
    elif text_lower == "ping":
        reply = "pong 🏓"
    elif text_lower.startswith("echo "):
        # Use raw_text so we preserve original casing after 'echo '
        reply = raw_text[5:]
    else:
        return

    line_bot_api.reply_message(
        event.reply_token,
        TextSendMessage(text=reply)
    )


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    app.run(host="0.0.0.0", port=port)
