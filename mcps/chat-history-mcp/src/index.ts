#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import pg from "pg";

const { Pool } = pg;

interface SearchArgs {
  conversationId: string;
  query: string;
  limit?: number;
}

interface MessageRow {
  messageid: string;
  userid: string;
  messagecontent: string;
  messagedate: Date;
  relevance?: number;
}

interface FormattedMessage {
  messageId: string;
  userId: string;
  role: "assistant" | "user";
  content: string;
  timestamp: Date;
}

// PostgreSQL connection pool
const pool = new Pool({
  host: process.env.DB_HOST || "localhost",
  port: parseInt(process.env.DB_PORT || "5432"),
  database: process.env.DB_NAME || "linebot",
  user: process.env.DB_USER || "postgres",
  password: process.env.DB_PASSWORD || "postgres",
});

// Tool definitions
const TOOLS = [
  {
    name: "search_chat_history",
    description:
      "Search through past conversation messages for specific information. Use this when the user references past conversations like 'what did you recommend yesterday?', 'that place you showed me', 'like last time', etc.",
    inputSchema: {
      type: "object",
      properties: {
        conversationId: {
          type: "string",
          description: "The LINE conversation ID to search messages from",
        },
        query: {
          type: "string",
          description: "The search query to find relevant past messages",
        },
        limit: {
          type: "number",
          description: "Maximum number of results to return (default: 20)",
          default: 20,
        },
      },
      required: ["conversationId", "query"],
    },
  },
];

// Create MCP server
const server = new Server(
  {
    name: "chat-history-mcp",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// List available tools
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return { tools: TOOLS };
});

// Handle tool calls
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  if (name === "search_chat_history") {
    const { conversationId, query, limit = 5 } = args as unknown as SearchArgs;

    try {
      // Strategy: PostgreSQL full-text search with tsvector for better relevance
      // Uses to_tsvector for word stemming (recommend → recommendation → recommended)
      // and ts_rank for relevance scoring
      
      // Clean and prepare search query (replace special chars, handle phrases)
      const searchQuery = query
        .toLowerCase()
        .replace(/[^\w\s]/g, ' ')  // Remove special chars
        .split(/\s+/)
        .filter((k) => k.length > 2)  // Filter short words
        .join(' | ');  // OR operator for full-text search
      
      if (!searchQuery) {
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify({
                found: false,
                message: "Invalid search query",
                results: []
              })
            }
          ]
        };
      }
      
      // EXCLUDE error messages and recall responses to prevent recursive issues
      // Use ts_rank to score relevance and order by it
      const result = await pool.query<MessageRow>(
        `SELECT 
          messageid,
          userid,
          messagecontent,
          messagedate,
          ts_rank(to_tsvector('english', messagecontent), to_tsquery('english', $2)) as relevance
        FROM messages
        WHERE lineconversationid = $1
          AND messagecontent IS NOT NULL
          AND messagedate >= NOW() - INTERVAL '7 days'
          AND messagecontent NOT LIKE '%Error calling Yelp API%'
          AND messagecontent NOT LIKE '%VALIDATION_ERROR%'
          AND messagecontent NOT LIKE '%Bad Request%'
          AND messagecontent NOT LIKE 'Here are the %'
          AND to_tsvector('english', messagecontent) @@ to_tsquery('english', $2)
        ORDER BY relevance DESC, messagedate DESC
        LIMIT $3`,
        [
          conversationId,
          searchQuery,
          limit * 5  // Get more results to find restaurant cards among other matches
        ]
      );

      if (result.rows.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify({
                found: false,
                message: "No relevant past messages found",
                results: [],
              }),
            },
          ],
        };
      }

      // Format results with role detection (userid = '-1' means bot/assistant)
      const formattedResults: FormattedMessage[] = result.rows.map((row) => ({
        messageId: row.messageid,
        userId: row.userid,
        role: row.userid === "-1" ? "assistant" : "user",
        content: row.messagecontent,
        timestamp: row.messagedate,
      }));
      
      // Prioritize restaurant cards (messages starting with 📍) for recall queries
      const restaurantCards = formattedResults.filter((r) => r.content.startsWith("📍 "));
      const otherResults = formattedResults.filter((r) => !r.content.startsWith("📍 "));
      const results = [...restaurantCards, ...otherResults].slice(0, limit);

      const response = {
        found: true,
        resultCount: results.length,
        results: results,
      };

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(response, null, 2),
          },
        ],
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Unknown error";
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify({
              error: errorMessage,
              found: false,
              results: [],
            }),
          },
        ],
        isError: true,
      };
    }
  }

  throw new Error(`Unknown tool: ${name}`);
});

// Start server
async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Chat History MCP Server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
