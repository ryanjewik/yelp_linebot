# Yelp LINE Bot

An AI-powered restaurant discovery chatbot for LINE that helps users find the perfect dining spot based on their preferences, dietary restrictions, and past conversations. Built with Spring Boot, PostgreSQL, and integrated with Yelp Fusion AI API, OpenAI GPT-4o, and Model Context Protocol (MCP) servers.

🌐 **Live Demo**: [https://yelplinebot.win](https://yelplinebot.win)

## 🎯 Overview

The Yelp LINE Bot is a conversational assistant that lives in your LINE messaging app. It combines the power of Yelp's comprehensive restaurant database with AI-driven natural language understanding to deliver personalized dining recommendations.

### Key Features

- 🍽️ **Smart Restaurant Discovery**: Natural language queries like "find good vegan sushi in SF under $30"
- 👥 **Group Preferences**: Automatically aggregates preferences from all group members' likes/dislikes
- 💬 **Conversation Memory**: Recalls past recommendations and learns from your interaction history
- 🎯 **Dual Personalization**: Combines explicit preferences (diet, allergies, favorites) with learned taste patterns (likes/dislikes)
- 💾 **Neo4j Graph Database**: Stores user preferences, restaurant likes/dislikes, and relationship patterns
- 🎴 **Rich Flex Messages**: Beautiful restaurant cards with images, ratings, and interactive Like/Dislike buttons
- 📊 **Real-time Preference Learning**: Every like/dislike refines future recommendations
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
│  + Tailwind  │      │  • Yelp Fusion AI API v2    │
│              │      │  • OpenAI GPT-4o Service    │
│  Demo Chat   │      │  • Flex Message Builder     │
│  Component   │      │  • Neo4j Service            │
└──────────────┘      └─────────────────────────────┘
                                 │
                    ┌────────────┼────────────┬──────────┐
                    ▼            ▼            ▼          ▼
            ┌─────────────┐  ┌──────┐  ┌──────────┐  ┌─────────┐
            │ PostgreSQL  │  │ MCP  │  │ MCP      │  │ Neo4j   │
            │             │  │ User │  │ Chat     │  │ Aura    │
            │ • Users     │  │ Prefs│  │ History  │  │         │
            │ • Messages  │  │(Node)│  │ (Node.js)│  │ • Users │
            │ • Convos    │  └──────┘  └──────────┘  │ • Rests │
            │ • Aggregates│                           │ • LIKES │
            └─────────────┘                           │ • DISLIKES
                                                      └─────────┘
```

### Technology Stack

**Frontend**:
- React 18 with TypeScript
- Vite for build tooling
- Tailwind CSS v4 with Noto Sans font
- Demo chat component with Yelp AI integration

**Backend**:
- Spring Boot 3.5.9 (Java 17)
- PostgreSQL 18 (conversation data & aggregates cache)
- Neo4j Aura (user preferences graph database)
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
Simply ask in natural language! No commands needed:
```
"Can you recommend a good sushi place in San Diego?"
"Find me cheap Mexican food near downtown"
"Where should we go for a romantic dinner anniversary?"
```

The bot will:
- Show restaurant recommendations as rich Flex Message cards
- Include AI reasoning based on your group's actual taste preferences
- Provide Like 👍 and Dislike 👎 buttons to refine future recommendations

### Interactive Buttons
After receiving recommendations:
- **👍 Like**: Records your preference in Neo4j, updates group aggregates
- **👎 Dislike**: Records avoidance, prevents similar recommendations
- **🔗 View on Yelp**: Opens restaurant page in Yelp app/web

Each interaction shows live counts:
```
Cesarina
👍 2 | 👎 1
```

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
For restaurant queries, the system:
- Launches OpenAI GPT-4o with function calling
- GPT-4o can call MCP servers via subprocess:
  - **User Preferences MCP**: Retrieves dietary restrictions, allergies, price range, and favorite cuisines for all group members
  - **Chat History MCP**: Searches past conversations if user references previous recommendations

### 3. Yelp API Integration with AI Reasoning
- Extracted context (location, cuisine, preferences) is sent to Yelp Fusion AI API v2
- Uses `with_reasoning: true` parameter to get AI explanations for each recommendation
- Yelp returns up to 3 restaurant matches with business details and reasoning
- Conversation ID is tracked for follow-up queries

### 4. Dual-Source Preference Enhancement
The system combines **two types of preferences** for maximum personalization:

**User-Set Preferences** (explicit from commands):
- Retrieves from PostgreSQL: dietary restrictions, allergies, favorite cuisines, price range
- Set via `/diet`, `/allergies`, `/favorites`, `/price` commands
- Represents what users explicitly tell the bot they want

**Learned Preferences** (implicit from behavior):
- Queries Neo4j for conversation aggregates: liked cuisines, average price level
- Built from Like/Dislike button interactions over time
- Represents actual taste patterns from real choices

**Priority Logic**:
- Cuisine matches: Checks user-set favorites FIRST → then learned likes
- Price alignment: Uses explicit price preference → falls back to learned average
- Combines both sources in reasoning:
  - "✓ Matches your favorite Japanese cuisine!" (explicit)
  - "✓ You've liked Italian before!" (learned)
  - "✓ Matches your preferred $$ range" (explicit)
  - "Dietary: vegan, gluten-free" (explicit)

### 5. Rich Flex Message Generation
- Builds LINE Flex Messages with restaurant cards
- Each card includes:
  - Hero image (restaurant photo)
  - Name, rating ⭐, price level, cuisine 🍽️
  - Address 📍, phone 📞
  - Enhanced reasoning with preference indicators
  - "View on Yelp" link button
  - Like 👍 (green) and Dislike 👎 (Yelp red) buttons
- Messages are sent via LINE Push API

### 6. Graph Database Updates (Neo4j)
When users click Like/Dislike:
- Records relationship in Neo4j: `(User)-[:LIKES|DISLIKES]->(Restaurant)`
- Stores restaurant metadata: cuisine, price level, name
- Updates PostgreSQL conversation aggregates cache
- Queries Neo4j for like/dislike counts
- Sends ratio message: `"Cesarina\n👍 2 | 👎 1"`

### 7. Preference Aggregation & Storage
**PostgreSQL** (explicit preferences):
- Stores user-set preferences per user: diet, allergies, favorite cuisines, price range
- Updated via `/diet`, `/allergies`, `/favorites`, `/price` commands
- Retrieved instantly for reasoning enhancement

**Neo4j Graph Database** (learned preferences):
- Stores: `(User)-[:LIKES|DISLIKES]->(Restaurant)` relationships
- Restaurant nodes contain: cuisine, price level, name
- Aggregates computed on-demand:
  - Top cuisines (from LIKES relationships across all group members)
  - Strong avoids (from DISLIKES relationships)
  - Average price level (from liked restaurants)

**Conversation Aggregates Cache** (PostgreSQL):
- Caches Neo4j aggregate results per conversation
- Updates after each Like/Dislike action
- Provides fast access to group-wide learned preferences


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