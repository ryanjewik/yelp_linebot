# Chat History MCP Server

Model Context Protocol server for searching chat history from PostgreSQL database.

## Features

- **Tool**: `search_chat_history` - Search past conversation messages for specific information

## Usage

This MCP server is designed to be called by OpenAI's function calling mechanism when users reference past conversations.

### Tool: search_chat_history

Searches through past messages in a conversation for relevant information.

**Input:**
- `conversationId` (string, required): The LINE conversation ID
- `query` (string, required): The search query
- `limit` (number, optional): Maximum results (default: 5)

**Output:**
```json
{
  "found": true,
  "resultCount": 3,
  "results": [
    {
      "messageId": "msg123",
      "userId": "U123",
      "role": "assistant",
      "content": "Here are some restaurants...",
      "timestamp": "2025-12-01T00:00:00Z"
    }
  ]
}
```

## Environment Variables

- `DB_HOST`: PostgreSQL host (default: localhost)
- `DB_PORT`: PostgreSQL port (default: 5432)
- `DB_NAME`: Database name (default: linebot)
- `DB_USER`: Database user (default: postgres)
- `DB_PASSWORD`: Database password (default: postgres)

## Development

```bash
npm install
DB_HOST=localhost DB_PASSWORD=yourpass node index.js
```
