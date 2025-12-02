# User Preferences MCP Server

Model Context Protocol server for retrieving user preferences from PostgreSQL database.

## Features

- **Tool**: `get_user_preferences` - Retrieve user dietary restrictions, allergies, price preferences, and favorite cuisines

## Usage

This MCP server is designed to be called by OpenAI's function calling mechanism to gather user context when making restaurant recommendations.

### Tool: get_user_preferences

Retrieves stored user preferences from the database.

**Input:**
- `userId` (string, required): The LINE user ID

**Output:**
```json
{
  "found": true,
  "preferences": {
    "favoriteCuisines": ["italian", "japanese"],
    "allergies": ["peanuts", "shellfish"],
    "priceRange": 2,
    "diet": ["vegetarian"]
  }
}
```

## Environment Variables

- `DB_HOST`: PostgreSQL host (default: localhost)
- `DB_PORT`: PostgreSQL port (default: 5432)
- `DB_NAME`: Database name (default: linebot)
- `DB_USER`: Database user (default: postgres)
- `DB_PASSWORD`: Database password (default: postgres)

## Running with Docker

```bash
docker compose up -d user-prefs-mcp
```

## Development

```bash
npm install
DB_HOST=localhost DB_PASSWORD=yourpass node index.js
```
