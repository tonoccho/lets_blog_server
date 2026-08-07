import { getOllamaClient } from '../clients/ollamaClient.js';
import { createLogger } from '../utils/logger.js';

const logger = createLogger('designSuggestion');

/**
 * Generate a design suggestion based on design brief
 * @param {string} designBrief - The design requirement brief
 * @param {Object} context - Additional context (project, brand, audience)
 * @returns {Promise<Object>} - Design suggestion with properties and CSS
 */
export async function designSuggestionHandler(designBrief, context = {}) {
  logger.info(`Design suggestion requested | Brief: "${designBrief}"`);

  const ollama = getOllamaClient();

  // Validate input
  if (!designBrief || designBrief.trim().length === 0) {
    throw new Error('designBrief cannot be empty');
  }

  if (designBrief.length > 500) {
    throw new Error('designBrief exceeds maximum length of 500 characters');
  }

  const { project = 'Let\'s Blog', brand = 'modern', audience = 'developers' } = context;

  // Build the prompt
  const prompt = buildDesignSuggestionPrompt(designBrief, project, brand, audience);

  try {
    // Generate design suggestion
    const result = await ollama.generateJSON(prompt);

    // Validate response structure
    validateDesignSuggestionResponse(result);

    logger.info('Design suggestion generated successfully');
    return result;
  } catch (error) {
    logger.error(`Design suggestion generation failed: ${error.message}`);
    throw error;
  }
}

/**
 * Build the prompt for design suggestion
 */
function buildDesignSuggestionPrompt(designBrief, project, brand, audience) {
  return `You are an expert UI/UX designer for modern web applications.

Generate a design proposal for the following component:

Brief: ${designBrief}
Project: ${project}
Brand Style: ${brand}
Target Audience: ${audience}

Provide your response as a JSON object with the following structure:
{
  "suggestion": "A detailed text description of the design proposal",
  "properties": {
    "colors": ["#RRGGBB", "#RRGGBB"],
    "typography": {
      "font": "font family name",
      "size": 16,
      "weight": 400,
      "lineHeight": 1.5
    },
    "spacing": {
      "padding": "12px 16px",
      "margin": "0",
      "gap": "8px",
      "borderRadius": "8px"
    },
    "shadow": "0 2px 8px rgba(0,0,0,0.1)",
    "border": {
      "width": "1px",
      "style": "solid",
      "color": "#RRGGBB"
    }
  },
  "css": "CSS code snippet for the component",
  "reasoning": "Explanation of design choices"
}

Respond with only valid JSON, no markdown or explanations.`;
}

/**
 * Validate design suggestion response
 */
function validateDesignSuggestionResponse(response) {
  if (!response.suggestion) {
    throw new Error('Response must include "suggestion" field');
  }
  if (!response.properties) {
    throw new Error('Response must include "properties" field');
  }
  if (!response.properties.colors || !Array.isArray(response.properties.colors)) {
    throw new Error('Properties must include "colors" array');
  }
  if (!response.css) {
    throw new Error('Response must include "css" field');
  }
}
