# Penpot Design MCP Server

AI-powered design suggestion server for Penpot using Ollama LLM.

## Overview

This MCP (Model Context Protocol) server bridges Penpot (design tool) and Ollama (LLM) to provide AI-assisted design features:

- **Design Suggestion**: Generate component designs from text briefs
- **Component Improvement**: Get actionable improvement suggestions for existing components
- **Color Palette Generation**: Create accessible color palettes based on brand descriptions
- **Typography Suggestions**: Recommend appropriate fonts and text styles

## Architecture

```
Penpot UI
   ↓ HTTP/JSON
MCP Server (Node.js)
   ↓ HTTP
Ollama API (qwen2.5:7b-instruct)
```

## Installation

### Prerequisites

- Node.js >= 20.0.0
- Ollama running on `http://ollama:11434` (or configured via environment)
- Penpot instance running

### Setup

```bash
cd mcp-server

# Install dependencies
npm install

# Create .env file
cp .env.example .env

# Edit .env with your configuration
nano .env
```

## Environment Variables

```bash
# Server
MCP_PORT=3000
MCP_LOG_LEVEL=info

# Ollama Configuration
OLLAMA_BASE_URL=http://ollama:11434
OLLAMA_MODEL=qwen2.5:7b-instruct
OLLAMA_REQUEST_TIMEOUT=30000

# Penpot (optional)
PENPOT_API_BASE_URL=http://penpot:80
PENPOT_API_TOKEN=
```

## Running the Server

### Development Mode

```bash
npm run dev
```

Uses `nodemon` for auto-restart on file changes.

### Production Mode

```bash
npm start
```

## API Endpoints

### Health Check

```bash
GET /health
```

Response:
```json
{
  "status": "ok",
  "service": "penpot-design-mcp-server",
  "version": "0.1.0"
}
```

### Design Suggestion

```bash
POST /api/design-suggestion
Content-Type: application/json

{
  "design_brief": "Primary button, blue, medium size",
  "context": {
    "project": "Let's Blog",
    "brand": "modern",
    "audience": "developers"
  }
}
```

Response:
```json
{
  "suggestion": "...",
  "properties": {
    "colors": ["#0066CC", "#ffffff"],
    "typography": {...},
    "spacing": {...},
    "shadow": "0 2px 8px rgba(0,0,0,0.1)"
  },
  "css": "...",
  "reasoning": "..."
}
```

### Improve Component

```bash
POST /api/improve-component

{
  "component_name": "PrimaryButton",
  "current_design": "/* CSS */",
  "improvement_areas": ["accessibility", "responsiveness"],
  "design_context": "dark-mode support needed"
}
```

### Generate Color Palette

```bash
POST /api/generate-color-palette

{
  "brand_description": "tech startup, innovative, trustworthy",
  "base_color": "#0066CC",
  "accessibility": true,
  "dark_mode_support": true
}
```

### Suggest Typography

```bash
POST /api/suggest-typography

{
  "design_context": "web admin dashboard",
  "content_type": "body text",
  "accessibility": true,
  "locale": "en"
}
```

## Testing

### Run Tests

```bash
npm test
```

### Run Tests in Watch Mode

```bash
npm run test:watch
```

### Test Coverage

```bash
npm run test:coverage
```

## Docker Deployment

### Build Image

```bash
docker build -t penpot-design-mcp:latest .
```

### Run Container

```bash
docker run -d \
  -p 3000:3000 \
  -e OLLAMA_BASE_URL=http://ollama:11434 \
  -e MCP_LOG_LEVEL=info \
  --network lbs-net \
  --name lbs-mcp-penpot \
  penpot-design-mcp:latest
```

### docker-compose Integration

```yaml
mcp-penpot:
  build:
    context: ./mcp-server
    dockerfile: Dockerfile
  container_name: lbs-mcp-penpot
  restart: unless-stopped
  environment:
    OLLAMA_BASE_URL: http://ollama:11434
    OLLAMA_MODEL: qwen2.5:7b-instruct
    MCP_PORT: 3000
    MCP_LOG_LEVEL: info
  depends_on:
    - ollama
  networks:
    - lbs-net
```

## Logging

Logs are written to:
- Console: All levels (colored output)
- `logs/error.log`: Errors only
- `logs/combined.log`: All levels

Set `MCP_LOG_LEVEL` environment variable to control verbosity:
- `error`: Errors only
- `warn`: Warnings and errors
- `info`: Information, warnings, errors (default)
- `debug`: Detailed debugging information

## Performance Tuning

### Timeout Configuration

Adjust `OLLAMA_REQUEST_TIMEOUT` (in milliseconds) based on your setup:
- Fast GPU: 5000-10000ms
- CPU inference: 20000-30000ms

### Model Selection

For faster responses, consider:
- `mistral:7b` - Faster, lower quality
- `neural-chat:7b` - Good balance
- `qwen2.5:7b-instruct` - Recommended (default)

## Troubleshooting

### Ollama Connection Error

```
Error: ECONNREFUSED 127.0.0.1:11434
```

Check:
1. Ollama is running: `docker ps | grep ollama`
2. `OLLAMA_BASE_URL` is correct
3. Network connectivity: `curl http://ollama:11434/api/tags`

### Timeout Errors

```
Error: Request timeout
```

Solutions:
1. Increase `OLLAMA_REQUEST_TIMEOUT`
2. Check Ollama model is loaded
3. Verify GPU availability: `nvidia-smi`

### JSON Parse Errors

```
Error: Invalid JSON response from Ollama
```

Possible causes:
1. Model response format changed
2. Prompt needs adjustment
3. Model hallucination - retry

## Project Structure

```
mcp-server/
├── src/
│   ├── server.js              # Main Express server
│   ├── clients/
│   │   └── ollamaClient.js    # Ollama integration
│   ├── tools/
│   │   ├── designSuggestion.js
│   │   ├── improveComponent.js
│   │   ├── generateColorPalette.js
│   │   ├── suggestTypography.js
│   │   └── *.test.js          # Unit tests
│   └── utils/
│       └── logger.js          # Logging utility
├── package.json
├── Dockerfile
├── .env.example
└── README.md
```

## Future Enhancements

- [ ] Redis caching for design suggestions
- [ ] Prometheus metrics integration
- [ ] User feedback mechanism for model improvement
- [ ] Support for multiple Ollama models
- [ ] Brave Search integration for design trends
- [ ] Component design template library
- [ ] Figma/Sketch export support

## License

MIT

## Support

For issues or questions:
1. Check logs: `docker logs lbs-mcp-penpot`
2. Verify Ollama: `curl http://ollama:11434/api/tags`
3. Test endpoint: `curl -X POST http://localhost:3000/api/design-suggestion -d '{"design_brief":"button"}'`
