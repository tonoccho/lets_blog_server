import axios from 'axios';
import { createLogger } from '../utils/logger.js';

const logger = createLogger('ollamaClient');

class OllamaClient {
  constructor(baseUrl, model, timeout = 30000) {
    this.baseUrl = baseUrl || process.env.OLLAMA_BASE_URL || 'http://ollama:11434';
    this.model = model || process.env.OLLAMA_MODEL || 'qwen2.5:7b-instruct';
    this.timeout = timeout;
    this.client = axios.create({
      baseURL: this.baseUrl,
      timeout: this.timeout,
    });
  }

  /**
   * Check Ollama connectivity
   */
  async healthCheck() {
    try {
      const response = await this.client.get('/api/tags');
      logger.info('Ollama health check passed');
      return true;
    } catch (error) {
      logger.error(`Ollama health check failed: ${error.message}`);
      throw new Error(`Ollama unavailable: ${error.message}`);
    }
  }

  /**
   * Generate text using Ollama
   * @param {string} prompt - The prompt to send to the model
   * @param {Object} options - Additional options (temperature, top_k, etc.)
   * @returns {Promise<string>} - The model's response
   */
  async generate(prompt, options = {}) {
    try {
      const startTime = Date.now();

      const payload = {
        model: this.model,
        prompt: prompt,
        stream: false,
        ...options,
      };

      logger.info(`Ollama request | Model: ${this.model} | Prompt length: ${prompt.length}`);

      const response = await this.client.post('/api/generate', payload);

      const duration = Date.now() - startTime;
      logger.info(
        `Ollama response | Duration: ${duration}ms | Output length: ${response.data.response.length}`
      );

      return response.data.response;
    } catch (error) {
      logger.error(`Ollama generation error: ${error.message}`);
      throw new Error(`Ollama generation failed: ${error.message}`);
    }
  }

  /**
   * Generate JSON output from Ollama
   * @param {string} prompt - The prompt to send to the model
   * @param {Object} options - Additional options
   * @returns {Promise<Object>} - Parsed JSON response
   */
  async generateJSON(prompt, options = {}) {
    const systemPrompt = `You MUST respond with valid JSON only. No markdown, no explanations, just raw JSON.`;
    const fullPrompt = `${systemPrompt}\n\n${prompt}`;

    const response = await this.generate(fullPrompt, options);

    try {
      // Remove markdown code blocks if present
      let jsonString = response.trim();
      if (jsonString.startsWith('```')) {
        jsonString = jsonString
          .replace(/```json?\n?/g, '')
          .replace(/```/g, '')
          .trim();
      }

      const parsedJSON = JSON.parse(jsonString);
      logger.info('JSON parsing successful');
      return parsedJSON;
    } catch (parseError) {
      logger.error(`Failed to parse JSON from Ollama response: ${parseError.message}`);
      logger.error(`Raw response: ${response}`);
      throw new Error(`Invalid JSON response from Ollama: ${parseError.message}`);
    }
  }

  /**
   * Set model
   */
  setModel(model) {
    this.model = model;
    logger.info(`Model changed to: ${model}`);
  }
}

// Singleton instance
let instance = null;

export function getOllamaClient() {
  if (!instance) {
    instance = new OllamaClient();
  }
  return instance;
}

export default OllamaClient;
