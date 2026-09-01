import { getOllamaClient } from '../clients/ollamaClient.js';
import { createLogger } from '../utils/logger.js';

const logger = createLogger('improveComponent');

/**
 * Generate improvement suggestions for a component
 * @param {string} componentName - The component name
 * @param {string} currentDesign - Current CSS/design definition
 * @param {Array<string>} improvementAreas - Areas to focus on
 * @param {string} designContext - Design context/requirements
 * @returns {Promise<Object>} - Improvement suggestions
 */
export async function improveComponentHandler(
  componentName,
  currentDesign,
  improvementAreas = [],
  designContext = ''
) {
  logger.info(
    `Improve component requested | Component: ${componentName} | Focus areas: ${improvementAreas.join(', ')}`
  );

  const ollama = getOllamaClient();

  // Validate input
  if (!componentName || !currentDesign) {
    throw new Error('componentName and currentDesign are required');
  }

  const defaultAreas = ['accessibility', 'responsiveness', 'dark-mode'];
  const areasToReview = improvementAreas.length > 0 ? improvementAreas : defaultAreas;

  // Build the prompt
  const prompt = buildImprovementPrompt(componentName, currentDesign, areasToReview, designContext);

  try {
    const result = await ollama.generateJSON(prompt);
    validateImprovementResponse(result);

    logger.info('Component improvement suggestions generated successfully');
    return result;
  } catch (error) {
    logger.error(`Improve component generation failed: ${error.message}`);
    throw error;
  }
}

function buildImprovementPrompt(componentName, currentDesign, improvementAreas, designContext) {
  return `You are a design system expert reviewing a UI component.

Component: ${componentName}

Current CSS/Design:
\`\`\`css
${currentDesign}
\`\`\`

Review focus areas: ${improvementAreas.join(', ')}
Context: ${designContext || 'General web application'}

Provide specific, actionable improvements in the following JSON format:
{
  "component_name": "${componentName}",
  "improvements": [
    {
      "area": "Area name (e.g., 'accessibility', 'responsiveness')",
      "issue": "Detailed description of the issue",
      "suggestion": "Concrete improvement suggestion",
      "css_change": "CSS code snippet showing the change",
      "impact": "Explanation of the positive impact"
    }
  ],
  "overall_score_before": 7.5,
  "overall_score_after": 9.2,
  "summary": "Overall assessment and key recommendations"
}

Respond with only valid JSON, no markdown or explanations.`;
}

function validateImprovementResponse(response) {
  if (!response.improvements || !Array.isArray(response.improvements)) {
    throw new Error('Response must include "improvements" array');
  }
  if (typeof response.overall_score_before !== 'number' || response.overall_score_before < 0 || response.overall_score_before > 10) {
    throw new Error('overall_score_before must be a number between 0 and 10');
  }
}
