# AI Agent 

A Java-based AI agent developed as part of my Java Development studies at IT-Högskolan.

Built with **Java 27**, the agent communicates with large language models (LLMs) through OpenRouter and can execute tools, interact with files, and connect to external services using the Model Context Protocol (MCP).

## Features

- **AI Chat** – Conversational memory and error handling with automatic retries.
- **Tool Execution** – Executes Java functions requested by the AI.
- **File Management** – Reads, lists, and creates files with user confirmation.
- **MCP Integration** – Connects to custom MCP servers.
- **Database Access** – Supports read-only MySQL queries.
- **Custom Skills** – Loads task-specific instructions from Markdown files.

## Technologies

Java 27 · Maven · Jackson 3 · OpenRouter API · MCP · MySQL

## Getting Started

1. Clone the repository and open it in IntelliJ IDEA.
2. Make sure Java 27
3. API Key Required: This project uses OpenRouter for LLM integration. To run the agent, configure your own OpenRouter API key using the OPENROUTER_API_KEY environment variable.
