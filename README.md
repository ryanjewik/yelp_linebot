# Yelp LINE Bot

A LINE messaging bot that provides restaurant recommendations powered by Yelp Fusion AI API.

## Architecture

### Services

- **linebot-bridge** (Spring Boot 3.5): Main application handling LINE messages and Yelp API calls
- **linebot-db** (PostgreSQL 18): Database for storing conversations, messages, and user preferences

### Key Features

- **Direct Yelp Integration**: Native Java implementation calling Yelp Fusion AI API directly
- **Hybrid Approach**: 
  - OpenAI GPT-4o for context gathering (user preferences, chat history search)
  - Direct Yelp API calls for reliable restaurant search
- **User Preferences**: Dietary restrictions, allergies, price range, favorite cuisines
- **Conversation History**: Chat history tracking with Yelp's conversation ID system
- **Rich Responses**: Business details, ratings, photos, hours, amenities

## Setup

1. Copy `.env.example` to `.env` and fill in your credentials:
   - `LINE_CHANNEL_SECRET` and `LINE_CHANNEL_ACCESS_TOKEN`
   - `YELP_API_KEY`
   - `OPENAI_API_KEY`
   - Database credentials

2. Build and start services:
   ```bash
   docker compose up -d
   ```

## User Commands

- `/yelp <query>` - Search for restaurants
- `/diet <restrictions>` - Set dietary restrictions (e.g., vegan, vegetarian)
- `/allergies <allergies>` - Set allergies (e.g., peanuts, shellfish)
- `/price <1-4>` - Set price preference (1=$, 2=$$, 3=$$$, 4=$$$$)
- `/favorites <cuisines>` - Set favorite cuisines
- `/prefs` - View current preferences

## Recent Changes

### Migration from Python Flask MCP to Java

**Date**: December 1, 2025

The Yelp API integration has been migrated from a separate Python Flask "MCP" server to native Java services within the Spring Boot application:

#### Before:
- Separate Python Flask container (`yelp-mcp`)
- HTTP calls from Java to Python service
- Additional container overhead

#### After:
- `YelpApiService.java`: Direct Yelp Fusion AI API calls
- `YelpResponseFormatter.java`: Response formatting in Java
- Removed Python dependency
- Simplified architecture

#### Benefits:
- ✅ Fewer moving parts (1 less container)
- ✅ Better error handling in Java
- ✅ Easier debugging and logging
- ✅ Reduced latency (no inter-container HTTP calls)
- ✅ Consistent codebase (all Java)

## MCP Servers (Node.js)

### User Preferences MCP ✅

**Status**: Implemented  
**Location**: `mcps/user-prefs-mcp/`  
**Purpose**: Retrieves preferences from ALL members in a conversation

**Tool**: `get_user_preferences`
- Gets preferences for all conversation members (via `chat_members` table)
- Aggregates dietary restrictions, allergies, favorite cuisines
- Uses minimum price range (most budget-conscious member)
- Called by OpenAI during context gathering phase
- Runs as subprocess, communicates via JSON-RPC over stdio

### Chat History MCP ✅

**Status**: Implemented  
**Location**: `mcps/chat-history-mcp/`  
**Purpose**: Search past conversation messages for recall queries

**Tool**: `search_chat_history`
- Searches messages table for relevant past conversations
- Triggered when user references past recommendations: "what did you suggest yesterday?"
- Returns matching messages with timestamps
- Runs as subprocess, communicates via JSON-RPC over stdio

## Future Enhancements

- Vector embeddings for semantic chat history search
- Enhanced photo carousel display
- Multi-location comparison