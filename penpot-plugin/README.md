# Penpot AI Design Assistant Plugin

AI-powered design assistant plugin for Penpot, powered by Ollama LLM integrated with Let's Blog Server's MCP.

## Features

- 🎨 **Design Suggestion**: Generate component designs from text briefs
- 🔍 **Component Improvement**: Get actionable suggestions for existing components
- 🎯 **Color Palette Generation**: Create accessible color schemes
- 📝 **Typography Recommendations**: Get font and text style suggestions

## Installation

### Prerequisites

- Penpot running (local or remote)
- MCP Server running on `http://localhost:3000` (or configure the endpoint)
- Node.js >= 20 for building

### Build Plugin

```bash
cd penpot-plugin

# Install dependencies
npm install

# Build plugin and UI
npm run build
npm run build:ui
```

This generates:
- `plugin.js` - Plugin code
- `ui.js` - UI panel code

### Install in Penpot

1. Open Penpot
2. Go to **Plugins** → **Add Plugin**
3. Select `manifest.json` from this directory
4. Click **Install**

The "AI Design Assistant" plugin will appear in your plugins menu.

## Usage

### Design Suggestion

1. Click **AI Design Assistant** plugin
2. Go to **Design Suggestion** tab
3. Enter a design brief (e.g., "Primary button, blue, medium size")
4. Select brand style and audience
5. Click **Generate Design Suggestion**
6. Review the suggestion and click **Apply to Canvas**

### Improve Component

1. Select a component on your canvas
2. Go to **Improve Component** tab
3. Select focus areas (accessibility, responsiveness, dark mode, etc.)
4. Click **Get Improvements**
5. Review suggestions

### Color Palette

1. Go to **Color Palette** tab
2. Describe your brand (e.g., "tech startup, innovative, trustworthy")
3. Optionally select a base color
4. Choose accessibility and dark mode requirements
5. Click **Generate Palette**

### Typography

1. Go to **Typography** tab
2. Describe your design context (e.g., "web admin dashboard")
3. Select content type
4. Click **Suggest Typography**

## Configuration

The plugin communicates with MCP Server at `http://localhost:3000/api`.

To change the endpoint, edit `src/ui.ts`:

```typescript
const MCP_API_BASE = 'http://your-mcp-server:3000/api';
```

Then rebuild:

```bash
npm run build:ui
```

## API Endpoints

The plugin calls these MCP Server endpoints:

- `POST /api/design-suggestion`
- `POST /api/improve-component`
- `POST /api/generate-color-palette`
- `POST /api/suggest-typography`

See MCP Server README for full API documentation.

## Development

### Watch Mode

```bash
npm run dev
```

This rebuilds automatically on file changes.

### Project Structure

```
penpot-plugin/
├── src/
│   ├── plugin.ts      # Plugin main code (communicates with Penpot)
│   ├── ui.ts          # UI panel code (handles user interactions)
├── ui.html            # UI panel HTML
├── styles.css         # UI styles
├── manifest.json      # Plugin metadata
├── package.json
└── README.md
```

## Troubleshooting

### Plugin doesn't load

1. Check console for errors (DevTools → Console)
2. Verify `plugin.js` is built and exists
3. Check Penpot logs

### MCP Server connection error

```
Error: MCP server error: Failed to fetch
```

1. Verify MCP Server is running: `curl http://localhost:3000/health`
2. Check `MCP_API_BASE` URL in `ui.ts`
3. Ensure Penpot browser has CORS access to MCP server

### Design suggestions not working

1. Check MCP Server logs: `docker logs lbs-mcp-penpot`
2. Verify Ollama is running: `curl http://ollama:11434/api/tags`
3. Ensure Ollama model is loaded: `ollama pull qwen2.5:7b-instruct`

## Limitations

- Plugin runs in Penpot's sandboxed environment with limited permissions
- Design suggestions apply as frames; further customization needed
- Large components may take time to generate suggestions

## Future Enhancements

- [ ] Direct shape property updates (not just frames)
- [ ] Component library integration
- [ ] Design system sync with generated designs
- [ ] User feedback mechanism for AI model improvement
- [ ] Batch design generation
- [ ] Real-time collaboration features

## License

MIT
