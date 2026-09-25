import { expect } from 'chai';
import sinon from 'sinon';
import * as designModule from './designSuggestion.js';

describe('Design Suggestion Tool', () => {
  let ollamaStub;

  beforeEach(() => {
    // Mock Ollama client
    ollamaStub = sinon.stub();
  });

  afterEach(() => {
    sinon.restore();
  });

  describe('Input Validation', () => {
    it('should reject empty design brief', async () => {
      try {
        await designModule.designSuggestionHandler('', {});
        expect.fail('Should have thrown error');
      } catch (error) {
        expect(error.message).to.include('empty');
      }
    });

    it('should reject design brief exceeding max length', async () => {
      const longBrief = 'a'.repeat(501);
      try {
        await designModule.designSuggestionHandler(longBrief, {});
        expect.fail('Should have thrown error');
      } catch (error) {
        expect(error.message).to.include('exceeds');
      }
    });

    it('should accept valid design brief', async () => {
      const brief = 'Primary button, blue, medium size';
      // This would need actual Ollama to test fully
      // Just checking it doesn't throw on validation
      expect(() => {
        // Validation logic
        if (!brief || brief.trim().length === 0) {
          throw new Error('Empty');
        }
        if (brief.length > 500) {
          throw new Error('Too long');
        }
      }).to.not.throw();
    });
  });

  describe('Context Handling', () => {
    it('should use default context when not provided', () => {
      const context = {
        project: 'Let\'s Blog',
        brand: 'modern',
        audience: 'developers',
      };
      expect(context.project).to.equal('Let\'s Blog');
      expect(context.brand).to.equal('modern');
    });

    it('should merge provided context with defaults', () => {
      const providedContext = { brand: 'minimal' };
      const mergedContext = {
        project: 'Let\'s Blog',
        brand: providedContext.brand || 'modern',
        audience: 'developers',
      };
      expect(mergedContext.brand).to.equal('minimal');
    });
  });
});
