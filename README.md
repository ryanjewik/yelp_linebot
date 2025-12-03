# Yelp LINE Bot

An AI-powered restaurant discovery chatbot for LINE that helps users find the perfect dining spot based on their preferences, dietary restrictions, and past conversations. Built with Spring Boot, PostgreSQL, and integrated with Yelp Fusion AI API, OpenAI GPT-4o, and Model Context Protocol (MCP) servers.

🌐 **Live Demo**: [https://yelplinebot.win](https://yelplinebot.win)

## 🎯 Overview

The Yelp LINE Bot is a conversational assistant that lives in your LINE messaging app. It combines the power of Yelp's comprehensive restaurant database with AI-driven natural language understanding to deliver personalized dining recommendations.

### Key Features

- 🍽️ **Smart Restaurant Discovery**: Natural language queries like "find good vegan sushi in SF under $30"
- 👥 **Group Preferences**: Automatically considers dietary restrictions and preferences of all group members
- 💬 **Conversation Memory**: Recalls past recommendations and learns from your history
- 🎯 **Personalization**: Remembers your dietary needs, allergies, favorite cuisines, and budget
- 📸 **Rich Responses**: Restaurant photos, ratings, hours, amenities, and direct Yelp links
- 🌐 **Interactive Landing Page**: Try the bot with a live demo chat before adding on LINE

## 🏗️ Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                         Nginx Proxy                         │
│  • HTTPS/SSL termination (Cloudflare Origin Certs)         │
│  • Route: / → Landing Page                                 │
│  • Route: /api/* → Backend API                             │
│  • Route: /callback → LINE Webhook                         │
└─────────────────────────────────────────────────────────────┘
                    │                    │
        ┌───────────┴──────────┐        │
        ▼                      ▼        ▼
┌──────────────┐      ┌─────────────────────────────┐
│ Landing Page │      │   Spring Boot Backend       │
│              │      │   (linebot-bridge)          │
│  React 18    │      │                             │
│  + Vite      │      │  • LINE Webhook Handler     │
│  + Tailwind  │      │  • Yelp API Integration     │
│              │      │  • OpenAI GPT-4o Service    │
│  Demo Chat   │      │  • MCP Client Service       │
│  Component   │      │  • User Preferences         │
└──────────────┘      └─────────────────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
            ┌─────────────┐  ┌──────┐  ┌──────────┐
            │ PostgreSQL  │  │ MCP  │  │ MCP      │
            │             │  │ User │  │ Chat     │
            │ • Users     │  │ Prefs│  │ History  │
            │ • Messages  │  │ (Node│  │ (Node.js)│
            │ • Convos    │  │ .js) │  │          │
            └─────────────┘  └──────┘  └──────────┘
```

### Technology Stack

**Frontend**:
- React 18 with TypeScript
- Vite for build tooling
- Tailwind CSS v4 with Noto Sans font
- Demo chat component with Yelp AI integration

**Backend**:
- Spring Boot 3.5.9 (Java 17)
- PostgreSQL 18
- RestTemplate for HTTP clients
- Jackson for JSON processing

**Infrastructure**:
- Docker Compose for container orchestration
- Nginx as reverse proxy (Alpine)
- Cloudflare Origin certificates for SSL/TLS
- Deployed on AWS EC2 (Amazon Linux 2)

**APIs & Services**:
- LINE Messaging API (webhooks)
- Yelp Fusion AI API v2
- OpenAI GPT-4o (context gathering)
- Model Context Protocol (MCP) servers for database queries

## 💬 User Commands

### Restaurant Search
```
/yelp <query>
```
Examples:
- `/yelp vegan ramen in Tokyo`
- `/yelp cheap mexican food near me`
- `/yelp romantic restaurants for anniversary dinner`

### Preference Management
```
/diet <restrictions>    - Set dietary preferences (vegan, vegetarian, gluten-free, etc.)
/allergies <items>      - Set allergen information (peanuts, shellfish, dairy, etc.)
/price <1-4>            - Set budget level (1=$, 2=$$, 3=$$$, 4=$$$$)
/favorites <cuisines>   - Set favorite cuisines (Italian, Japanese, Thai, etc.)
/prefs                  - View your current preferences
```

### Other Commands
```
/help                   - Show all available commands
```

## 🛠️ How It Works

### 1. Message Reception
- User sends a message in LINE (DM or group chat)
- LINE forwards the webhook event to `/callback`
- Nginx proxies the request to Spring Boot backend
- Signature validation ensures request authenticity

### 2. Context Gathering (OpenAI + MCP)
For `/yelp` queries, the system:
- Launches OpenAI GPT-4o with function calling
- GPT-4o can call MCP servers via subprocess:
  - **User Preferences MCP**: Retrieves dietary restrictions, allergies, price range, and favorite cuisines for all group members
  - **Chat History MCP**: Searches past conversations if user references previous recommendations

### 3. Yelp API Integration
- Extracted context (location, cuisine, preferences) is sent to Yelp Fusion AI API
- Yelp returns restaurant matches with business details
- Conversation ID is tracked for follow-up queries

### 4. Response Formatting
- Restaurant data is formatted into rich text messages
- Includes: name, rating, price level, address, phone, hours, amenities
- Photos are attached when available
- Messages are sent back to LINE via reply or push API

### 5. Database Persistence
- Messages are stored in PostgreSQL for history
- User preferences are updated with each `/diet`, `/allergies`, etc. command
- Conversation context is maintained across sessions

## 📁 Project Structure

```
yelp_linebot/
├── landing-page/              # React landing page
│   ├── src/
│   │   ├── components/
│   │   │   └── DemoChat.tsx   # Interactive demo
│   │   ├── App.tsx
│   │   └── main.tsx
│   ├── Dockerfile
│   └── nginx.conf
│
├── linebot-bridge/            # Spring Boot backend
│   ├── src/main/java/com/ryanhideo/linebot/
│   │   ├── controller/
│   │   │   ├── LineCallbackController.java
│   │   │   └── DemoChatController.java
│   │   ├── service/
│   │   │   ├── LineMessageService.java
│   │   │   ├── YelpApiService.java
│   │   │   ├── OpenAIService.java
│   │   │   ├── McpClientService.java
│   │   │   └── UserPreferencesService.java
│   │   ├── config/
│   │   └── util/
│   ├── Dockerfile
│   └── pom.xml
│
├── mcps/                      # MCP Servers (Node.js)
│   ├── user-prefs-mcp/        # User preferences retrieval
│   └── chat-history-mcp/      # Conversation history search
│
├── nginx/                     # Reverse proxy configuration
│   ├── nginx.conf
│   └── ssl/
│       ├── origin-cert.pem    # Cloudflare Origin Certificate
│       └── origin-key.pem
│
├── docker-compose.yml         # Container orchestration
└── README.md
```

## 🔐 Security Features

- **HTTPS/TLS**: Cloudflare Origin certificates with TLSv1.2/1.3
- **Webhook Signature Verification**: LINE requests are validated using HMAC-SHA256
- **Rate Limiting**: Nginx rate limits API requests (10 req/s for API, 30 req/s general)
- **Security Headers**: HSTS, X-Frame-Options, X-Content-Type-Options, X-XSS-Protection
- **Environment Variables**: Sensitive credentials stored in `.env` (not committed)
- **Input Sanitization**: All user inputs are validated before processing


## 🧪 Testing

### Test Demo Chat
Visit [https://yelplinebot.win](https://yelplinebot.win) and use the interactive demo chat to test Yelp AI queries without adding the bot to LINE.

### Test LINE Integration
1. Scan the QR code on the landing page
2. Add the bot as a friend
3. Send: `/yelp good pizza in NYC`
4. Check backend logs for processing

### Verify Webhook
```bash
curl -X POST https://yelplinebot.win/callback \
  -H "Content-Type: application/json" \
  -H "X-Line-Signature: test" \
  -d '{"events":[]}'
```


## 👤 Author

**Ryan Jewik**
- Portfolio: [ryanjewik.com](https://ryanjewik.com)
- GitHub: [@ryanjewik](https://github.com/ryanjewik)
- LINE Bot: [Scan QR code at yelplinebot.win](https://yelplinebot.win)

## 🙏 Acknowledgments

- LINE Messaging API for webhook infrastructure
- Yelp Fusion AI API for restaurant data
- OpenAI GPT-4o for natural language understanding
- Model Context Protocol for database query abstraction
- Cloudflare for SSL/TLS certificates