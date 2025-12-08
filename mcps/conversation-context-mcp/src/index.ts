#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import pg from "pg";

const { Pool } = pg;

interface GetConversationContextArgs {
  conversationId: string;
}

interface ConversationAggregatesRow {
  lineconversationid: string;
  top_cuisines: string[] | null;
  strong_avoids: string[] | null;
  avg_price: number | null;
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
    name: "get_conversation_context",
    description:
      "Retrieve conversation-level aggregate preferences from Neo4j graph data. Returns top cuisines liked by members, strong avoids (disliked cuisines), and average price level. Use this to understand group dining preferences for restaurant recommendations.",
    inputSchema: {
      type: "object",
      properties: {
        conversationId: {
          type: "string",
          description: "The LINE conversation ID to get aggregate context for",
        },
      },
      required: ["conversationId"],
    },
  },
];

// Create MCP server
const server = new Server(
  {
    name: "conversation-context-mcp",
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

  if (name === "get_conversation_context") {
    const { conversationId } = args as unknown as GetConversationContextArgs;

    try {
      // Query conversation aggregates
      const result = await pool.query<ConversationAggregatesRow>(
        "SELECT lineconversationid, top_cuisines, strong_avoids, avg_price FROM conversations WHERE lineconversationid = $1",
        [conversationId]
      );

      if (result.rows.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify({
                found: false,
                message: "No conversation context found. This may be a new conversation.",
              }),
            },
          ],
        };
      }

      const row = result.rows[0];
      const topCuisines = row.top_cuisines || [];
      const strongAvoids = row.strong_avoids || [];
      const avgPrice = row.avg_price;

      // Format context string for prompt engineering
      let contextParts: string[] = [];

      if (topCuisines.length > 0) {
        contextParts.push(`Group likes: ${topCuisines.join(", ")}`);
      }

      if (strongAvoids.length > 0) {
        contextParts.push(`Group avoids: ${strongAvoids.join(", ")}`);
      }

      if (avgPrice !== null && avgPrice > 0) {
        const priceLabels = ["", "Budget-friendly", "Mid-range", "Upscale", "Fine dining"];
        const priceLabel = priceLabels[avgPrice] || "Unknown";
        contextParts.push(`Preferred price level: ${priceLabel} (${"$".repeat(avgPrice)})`);
      }

      const contextString = contextParts.length > 0 
        ? contextParts.join("\n") 
        : "No aggregate preferences available yet.";

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify({
              found: true,
              conversationId: conversationId,
              topCuisines: topCuisines,
              strongAvoids: strongAvoids,
              avgPrice: avgPrice,
              contextString: contextString,
            }),
          },
        ],
      };
    } catch (error) {
      console.error("Error fetching conversation context:", error);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify({
              found: false,
              error: error instanceof Error ? error.message : "Unknown error",
            }),
          },
        ],
        isError: true,
      };
    }
  }

  return {
    content: [
      {
        type: "text",
        text: JSON.stringify({ error: "Unknown tool" }),
      },
    ],
    isError: true,
  };
});

// Start the server
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Conversation Context MCP server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error in main():", error);
  process.exit(1);
});
