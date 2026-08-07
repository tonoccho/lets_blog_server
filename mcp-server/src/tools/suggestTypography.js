import { getOllamaClient } from '../clients/ollamaClient.js';
import { createLogger } from '../utils/logger.js';

const logger = createLogger('suggestTypography');

/**
 * Suggest typography based on design context
 * @param {string} designContext - Design context/page type
 * @param {string} contentType - Type of content (body, heading, label, etc.)
 * @param {boolean} accessibility - Accessibility requirement
 * @param {string} locale - Language locale (en, ja, etc.)
 * @returns {Promise<Object>} - Typography recommendations
 */
export async function suggestTypographyHandler(
  designContext,
  contentType = 'body text',
  accessibility = true,
  locale = 'en'
) {
  logger.info(
    `Suggest typography requested | Context: ${designContext} | Type: ${contentType} | Locale: ${locale}`
  );

  const ollama = getOllamaClient();

  if (!designContext || designContext.trim().length === 0) {
    throw new Error('designContext cannot be empty');
  }

  const prompt = buildTypographyPrompt(designContext, contentType, accessibility, locale);

  try {
    const result = await ollama.generateJSON(prompt);
    validateTypographyResponse(result);

    logger.info('Typography suggestions generated successfully');
    return result;
  } catch (error) {
    logger.error(`Suggest typography failed: ${error.message}`);
    throw error;
  }
}

function buildTypographyPrompt(designContext, contentType, accessibility, locale) {
  const localizeNote =
    locale === 'ja'
      ? 'For Japanese text, recommend appropriate Japanese fonts (e.g., Noto Sans JP, Hiragino Sans) with fallbacks.'
      : 'Use web-safe fonts with fallback options.';

  return `You are a typography expert for web design.

Design Context: ${designContext}
Content Type: ${contentType}
Accessibility Requirement: ${accessibility ? 'WCAG AA' : 'Standard'}
Language/Locale: ${locale}
${localizeNote}

Generate typography recommendations as JSON with the following structure:
{
  "recommendations": [
    {
      "name": "Heading 1",
      "font_family": "Inter",
      "font_size": 32,
      "font_weight": 700,
      "line_height": 1.2,
      "letter_spacing": "-0.5px",
      "text_transform": "none",
      "css": "font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; font-size: 32px; font-weight: 700; line-height: 1.2; letter-spacing: -0.5px;"
    },
    {
      "name": "Body",
      "font_family": "Inter",
      "font_size": 14,
      "font_weight": 400,
      "line_height": 1.6,
      "letter_spacing": "0px",
      "text_transform": "none",
      "css": "font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; font-size: 14px; font-weight: 400; line-height: 1.6;"
    }
  ],
  "fallback_fonts": ["Segoe UI", "Roboto", "sans-serif"],
  "loading_strategy": "swap",
  "accessibility_notes": "Line height >= 1.5 for body text, font size >= 14px, sufficient color contrast",
  "locale_specific": {
    "cjk_considerations": "CJK fonts may require adjusted line-height and character spacing"
  }
}

Respond with only valid JSON, no markdown or explanations.`;
}

function validateTypographyResponse(response) {
  if (!response.recommendations || !Array.isArray(response.recommendations)) {
    throw new Error('Response must include "recommendations" array');
  }
  if (response.recommendations.length === 0) {
    throw new Error('recommendations array cannot be empty');
  }
  response.recommendations.forEach((rec, index) => {
    if (!rec.font_family || typeof rec.font_size !== 'number') {
      throw new Error(
        `Recommendation ${index} must include font_family and numeric font_size`
      );
    }
  });
}
