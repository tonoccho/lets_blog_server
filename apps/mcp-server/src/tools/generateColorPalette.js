import { getOllamaClient } from '../clients/ollamaClient.js';
import { createLogger } from '../utils/logger.js';

const logger = createLogger('generateColorPalette');

/**
 * Generate color palette based on brand description
 * @param {string} brandDescription - Description of the brand
 * @param {string} baseColor - Optional base color (hex)
 * @param {boolean} accessibility - WCAG accessibility requirement
 * @param {boolean} darkModeSupport - Include dark mode colors
 * @returns {Promise<Object>} - Complete color palette
 */
export async function generateColorPaletteHandler(
  brandDescription,
  baseColor = '',
  accessibility = true,
  darkModeSupport = true
) {
  logger.info(
    `Generate color palette requested | Brand: "${brandDescription}" | Base: ${baseColor || 'auto'}`
  );

  const ollama = getOllamaClient();

  if (!brandDescription || brandDescription.trim().length === 0) {
    throw new Error('brandDescription cannot be empty');
  }

  const prompt = buildColorPalettePrompt(
    brandDescription,
    baseColor,
    accessibility,
    darkModeSupport
  );

  try {
    const result = await ollama.generateJSON(prompt);
    validateColorPaletteResponse(result);

    logger.info('Color palette generated successfully');
    return result;
  } catch (error) {
    logger.error(`Generate color palette failed: ${error.message}`);
    throw error;
  }
}

function buildColorPalettePrompt(brandDescription, baseColor, accessibility, darkModeSupport) {
  return `You are a color theory and accessible design expert.

Brand Description: ${brandDescription}
${baseColor ? `Base Color: ${baseColor}` : 'Base Color: [Auto-select based on brand]'}

Requirements:
- Accessibility: WCAG ${accessibility ? 'AA' : 'No specific requirement'}
- Dark mode support: ${darkModeSupport ? 'Yes' : 'No'}
- Professional and consistent appearance

Generate a complete color palette as JSON with the following structure:
{
  "primary": "#0066CC",
  "primary_light": "#3399FF",
  "primary_dark": "#003399",
  "secondary": "#FF6633",
  "accent": "#00AA44",
  "success": "#00AA44",
  "warning": "#FFAA00",
  "error": "#FF3333",
  "info": "#0099FF",
  "neutral_50": "#FAFAFA",
  "neutral_100": "#FFFFFF",
  "neutral_200": "#F5F5F5",
  "neutral_300": "#E0E0E0",
  "neutral_400": "#999999",
  "neutral_500": "#666666",
  "neutral_600": "#333333",
  "neutral_700": "#1A1A1A",
  "neutral_800": "#0D0D0D",
  ${darkModeSupport ? `"dark_mode": {
    "primary": "#66CCFF",
    "primary_light": "#99DDFF",
    "primary_dark": "#3399CC",
    "background": "#121212",
    "surface": "#1E1E1E",
    "text_primary": "#FFFFFF",
    "text_secondary": "#BDBDBD"
  },` : ''}
  "contrast_ratios": {
    "primary_on_white": 5.2,
    "primary_on_neutral_100": 4.8,
    "text_on_primary": 10.5
  },
  "usage_guidelines": {
    "primary": "Main brand color for primary actions and CTAs",
    "secondary": "Secondary actions and complementary elements",
    "success": "Positive actions, confirmations, successful states",
    "error": "Destructive actions, errors, warnings"
  }
}

Respond with only valid JSON, no markdown or explanations.`;
}

function validateColorPaletteResponse(response) {
  if (!response.primary || typeof response.primary !== 'string') {
    throw new Error('Response must include "primary" color');
  }
  if (!response.contrast_ratios) {
    throw new Error('Response must include "contrast_ratios"');
  }
  // Validate hex color format
  const hexPattern = /^#[0-9A-F]{6}$/i;
  if (!hexPattern.test(response.primary)) {
    throw new Error(`Invalid hex color format: ${response.primary}`);
  }
}
