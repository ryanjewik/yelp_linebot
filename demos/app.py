import os
from flask import Flask, request, abort
from dotenv import load_dotenv

from linebot import LineBotApi, WebhookHandler
from linebot.exceptions import InvalidSignatureError
from linebot.models import MessageEvent, TextMessage, TextSendMessage

# Load environment variables
load_dotenv()

CHANNEL_SECRET = os.getenv("LINE_CHANNEL_SECRET")
CHANNEL_ACCESS_TOKEN = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")

if CHANNEL_SECRET is None or CHANNEL_ACCESS_TOKEN is None:
    raise ValueError("LINE_CHANNEL_SECRET and LINE_CHANNEL_ACCESS_TOKEN must be set")

app = Flask(__name__)

line_bot_api = LineBotApi(CHANNEL_ACCESS_TOKEN)
handler = WebhookHandler(CHANNEL_SECRET)


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
    text = event.message.text.strip().lower()
    
    if text == "help":
        reply = (
            "Commands:\n"
            "- help: show this help\n"
            "- ping: test latency\n"
            "- echo <text>: I'll repeat your text"
        )
    elif text == "ping":
        reply = "pong 🏓"
    elif text.startswith("echo "):
        reply = text[5:]  # everything after "echo "
    else:
        reply = f"You said: {event.message.text}"

    line_bot_api.reply_message(
        event.reply_token,
        TextSendMessage(text=reply)
    )



if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    app.run(host="0.0.0.0", port=port)
