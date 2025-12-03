#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import pg from "pg";

const { Pool } = pg;

interface GetPreferencesArgs {
  conversationId: string;
}

interface MemberRow {
  userid: string;
}

interface PreferencesRow {
  userid: string;
  favoritecuisines: string[] | null;
  allergies: string[] | null;
  pricerangepref: number | null;
  diet: string[] | null;
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
    name: "get_user_preferences",
    description:
      "Retrieve user preferences for all members in a conversation, including dietary restrictions, allergies, price range, and favorite cuisines. Use this when you need to understand user preferences for restaurant recommendations.",
    inputSchema: {
      type: "object",
      properties: {
        conversationId: {
          type: "string",
          description: "The LINE conversation ID to get all members' preferences",
        },
      },
      required: ["conversationId"],
    },
  },
];

// Create MCP server
const server = new Server(
  {
    name: "user-prefs-mcp",
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

  if (name === "get_user_preferences") {
    const { conversationId } = args as unknown as GetPreferencesArgs;

    try {
      // Get all user IDs from the conversation (excluding bot userId = -1)
      const membersResult = await pool.query<MemberRow>(
        "SELECT userid FROM chat_members WHERE lineconversationid = $1 AND userid != '-1'",
        [conversationId]
      );

      if (membersResult.rows.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify({
                found: false,
                message: "No members found in this conversation",
              }),
            },
          ],
        };
      }

      // Get user IDs
      const userIds = membersResult.rows.map((row) => row.userid);

      // Get preferences for all users
      const prefsResult = await pool.query<PreferencesRow>(
        "SELECT userid, favoritecuisines, allergies, pricerangepref, diet FROM users WHERE userid = ANY($1)",
        [userIds]
      );

      // Aggregate preferences from all users
      const allCuisines = new Set<string>();
      const allAllergies = new Set<string>();
      const allDiet = new Set<string>();
      const priceRanges: number[] = [];

      prefsResult.rows.forEach((row) => {
        if (row.favoritecuisines) {
          row.favoritecuisines.forEach((c) => allCuisines.add(c));
        }
        if (row.allergies) {
          row.allergies.forEach((a) => allAllergies.add(a));
        }
        if (row.diet) {
          row.diet.forEach((d) => allDiet.add(d));
        }
        if (row.pricerangepref) {
          priceRanges.push(row.pricerangepref);
        }
      });

      // Use minimum price range (most budget-conscious)
      const minPriceRange = priceRanges.length > 0 ? Math.min(...priceRanges) : null;

      const response = {
        found: true,
        memberCount: userIds.length,
        preferences: {
          favoriteCuisines: Array.from(allCuisines),
          allergies: Array.from(allAllergies),
          priceRange: minPriceRange,
          diet: Array.from(allDiet),
        },
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
  console.error("User Preferences MCP Server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
