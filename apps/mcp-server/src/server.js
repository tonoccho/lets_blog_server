import express from 'express';
import dotenv from 'dotenv';
import { createLogger } from './utils/logger.js';
import { designSuggestionHandler } from './tools/designSuggestion.js';
import { improveComponentHandler } from './tools/improveComponent.js';
import { generateColorPaletteHandler } from './tools/generateColorPalette.js';
import { suggestTypographyHandler } from './tools/suggestTypography.js';

dotenv.config();

const app = express();
const logger = createLogger('server');

// Middleware
app.use(express.json());

// Request logging
app.use((req, res, next) => {
  logger.info(`${req.method} ${req.path}`);
  next();
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'penpot-design-mcp-server', version: '0.4.3-DEVELOP' });
});

// MCP Tool Endpoints

/**
 * Design Suggestion Tool
 * POST /api/design-suggestion
 */
app.post('/api/design-suggestion', async (req, res) => {
  try {
    const { design_brief, context } = req.body;

    if (!design_brief) {
      return res.status(400).json({ error: 'design_brief is required' });
    }

    const result = await designSuggestionHandler(design_brief, context || {});
    res.json(result);
  } catch (error) {
    logger.error(`Design suggestion error: ${error.message}`);
    res.status(500).json({
      error: 'Failed to generate design suggestion',
      message: error.message,
    });
  }
});

/**
 * Improve Component Tool
 * POST /api/improve-component
 */
app.post('/api/improve-component', async (req, res) => {
  try {
    const { component_name, current_design, improvement_areas, design_context } = req.body;

    if (!component_name || !current_design) {
      return res.status(400).json({
        error: 'component_name and current_design are required',
      });
    }

    const result = await improveComponentHandler(
      component_name,
      current_design,
      improvement_areas || [],
      design_context || ''
    );
    res.json(result);
  } catch (error) {
    logger.error(`Improve component error: ${error.message}`);
    res.status(500).json({
      error: 'Failed to improve component',
      message: error.message,
    });
  }
});

/**
 * Generate Color Palette Tool
 * POST /api/generate-color-palette
 */
app.post('/api/generate-color-palette', async (req, res) => {
  try {
    const {
      brand_description,
      base_color,
      accessibility = true,
      dark_mode_support = true,
    } = req.body;

    if (!brand_description) {
      return res.status(400).json({ error: 'brand_description is required' });
    }

    const result = await generateColorPaletteHandler(
      brand_description,
      base_color,
      accessibility,
      dark_mode_support
    );
    res.json(result);
  } catch (error) {
    logger.error(`Generate color palette error: ${error.message}`);
    res.status(500).json({
      error: 'Failed to generate color palette',
      message: error.message,
    });
  }
});

/**
 * Suggest Typography Tool
 * POST /api/suggest-typography
 */
app.post('/api/suggest-typography', async (req, res) => {
  try {
    const {
      design_context,
      content_type = 'body text',
      accessibility = true,
      locale = 'en',
    } = req.body;

    if (!design_context) {
      return res.status(400).json({ error: 'design_context is required' });
    }

    const result = await suggestTypographyHandler(
      design_context,
      content_type,
      accessibility,
      locale
    );
    res.json(result);
  } catch (error) {
    logger.error(`Suggest typography error: ${error.message}`);
    res.status(500).json({
      error: 'Failed to suggest typography',
      message: error.message,
    });
  }
});

// Error handling middleware
app.use((err, req, res, next) => {
  logger.error(`Unhandled error: ${err.message}`);
  res.status(500).json({
    error: 'Internal server error',
    message: err.message,
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Server startup
const PORT = process.env.MCP_PORT || 3000;
app.listen(PORT, () => {
  logger.info(`MCP Server started on port ${PORT}`);
  logger.info(`Ollama Base URL: ${process.env.OLLAMA_BASE_URL}`);
  logger.info(`Ollama Model: ${process.env.OLLAMA_MODEL}`);
});
