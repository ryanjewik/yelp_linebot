# Conversation Context MCP Server

Model Context Protocol (MCP) server that provides conversation-level aggregate preferences derived from Neo4j graph data.

## Purpose

This MCP server exposes group dining preferences computed from individual user likes/dislikes stored in Neo4j:
- **Top Cuisines**: Most liked cuisines across conversation members
- **Strong Avoids**: Most disliked cuisines to avoid
- **Average Price Level**: Preferred price range based on liked restaurants

## Tool

### `get_conversation_context`

Retrieves cached aggregate preferences for a conversation.

**Input:**
- `conversationId` (string): LINE conversation ID

**Output:**
```json
{
  "found": true,
  "conversationId": "...",
  "topCuisines": ["Italian", "Japanese"],
  "strongAvoids": ["Indian"],
  "avgPrice": 2,
  "contextString": "Group likes: Italian, Japanese\nGroup avoids: Indian\nPreferred price level: Mid-range ($$)"
}
```

## Setup

```bash
npm install
npm run build
npm start
```

## Environment Variables

- `DB_HOST`: PostgreSQL host (default: localhost)
- `DB_PORT`: PostgreSQL port (default: 5432)
- `DB_NAME`: Database name (default: linebot)
- `DB_USER`: Database user (default: postgres)
- `DB_PASSWORD`: Database password
