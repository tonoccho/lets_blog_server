/**
 * Penpot AI Design Assistant - UI Handler
 * Manages the UI panel and communication with the plugin
 */

const MCP_API_BASE = 'http://localhost:3000/api';

// Tab management
const tabButtons = document.querySelectorAll('.tab-button');
const tabContents = document.querySelectorAll('.tab-content');

tabButtons.forEach((button) => {
  button.addEventListener('click', () => {
    const tabName = button.getAttribute('data-tab');

    // Remove active class from all buttons and contents
    tabButtons.forEach((btn) => btn.classList.remove('active'));
    tabContents.forEach((content) => content.classList.remove('active'));

    // Add active class to clicked button and corresponding content
    button.classList.add('active');
    document.getElementById(tabName!)?.classList.add('active');
  });
});

// Design Suggestion handlers
const suggestButton = document.getElementById('suggestButton');
suggestButton?.addEventListener('click', async () => {
  const designBrief = (document.getElementById('designBrief') as HTMLTextAreaElement).value;
  const brandStyle = (document.getElementById('brandStyle') as HTMLSelectElement).value;
  const audience = (document.getElementById('audience') as HTMLSelectElement).value;

  if (!designBrief.trim()) {
    showStatus('Please enter a design brief', 'error');
    return;
  }

  showStatus('Generating design suggestion...', 'loading');

  try {
    const response = await fetch(`${MCP_API_BASE}/design-suggestion`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        design_brief: designBrief,
        context: { brand: brandStyle, audience: audience },
      }),
    });

    if (!response.ok) {
      throw new Error(`Server error: ${response.statusText}`);
    }

    const result = await response.json();
    displayDesignSuggestion(result);
    showStatus('Design suggestion generated!', 'success');
  } catch (error) {
    showStatus(`Error: ${(error as Error).message}`, 'error');
  }
});

function displayDesignSuggestion(result: any) {
  const resultBox = document.getElementById('suggestionResult');
  const suggestionText = document.getElementById('suggestionText');
  const suggestionCSS = document.getElementById('suggestionCSS');

  if (suggestionText) suggestionText.textContent = result.suggestion || '';
  if (suggestionCSS) suggestionCSS.textContent = result.css || '';

  resultBox?.classList.remove('hidden');

  const applyButton = document.getElementById('applyButton');
  applyButton?.addEventListener('click', () => {
    parent.postMessage(
      {
        type: 'design-suggestion',
        payload: result,
      },
      '*'
    );
  });
}

// Improve Component handlers
const improveButton = document.getElementById('improveButton');
improveButton?.addEventListener('click', async () => {
  const checkboxes = document.querySelectorAll(
    '#improve .checkbox-group input[type="checkbox"]:checked'
  );
  const improvementAreas = Array.from(checkboxes).map(
    (cb) => (cb as HTMLInputElement).value
  );

  if (improvementAreas.length === 0) {
    showStatus('Please select at least one focus area', 'error');
    return;
  }

  showStatus('Getting component improvements...', 'loading');

  try {
    // First, get current selection info
    parent.postMessage({ type: 'get-selection' }, '*');

    // Wait for selection info (in real implementation, handle via message listener)
    showStatus('Please select a component on the canvas first', 'error');
  } catch (error) {
    showStatus(`Error: ${(error as Error).message}`, 'error');
  }
});

// Color Palette handlers
const paletteButton = document.getElementById('paletteButton');
paletteButton?.addEventListener('click', async () => {
  const brandDescription = (
    document.getElementById('brandDescription') as HTMLTextAreaElement
  ).value;
  const baseColor = (document.getElementById('baseColor') as HTMLInputElement).value;
  const wcagCompliance = (
    document.getElementById('wcagCompliance') as HTMLInputElement
  ).checked;
  const darkModeSupport = (
    document.getElementById('darkModeSupport') as HTMLInputElement
  ).checked;

  if (!brandDescription.trim()) {
    showStatus('Please describe your brand', 'error');
    return;
  }

  showStatus('Generating color palette...', 'loading');

  try {
    const response = await fetch(`${MCP_API_BASE}/generate-color-palette`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        brand_description: brandDescription,
        base_color: baseColor,
        accessibility: wcagCompliance,
        dark_mode_support: darkModeSupport,
      }),
    });

    if (!response.ok) {
      throw new Error(`Server error: ${response.statusText}`);
    }

    const result = await response.json();
    displayColorPalette(result);
    showStatus('Color palette generated!', 'success');
  } catch (error) {
    showStatus(`Error: ${(error as Error).message}`, 'error');
  }
});

function displayColorPalette(result: any) {
  const resultBox = document.getElementById('paletteResult');
  const colorGrid = document.getElementById('colorGrid');

  if (!colorGrid) return;

  let html = '<div class="color-grid">';

  const colorKeys = [
    'primary',
    'primary_light',
    'primary_dark',
    'secondary',
    'success',
    'warning',
    'error',
    'neutral_300',
    'neutral_600',
  ];

  colorKeys.forEach((key) => {
    if (result[key]) {
      html += `
        <div class="color-item">
          <div class="color-swatch" style="background: ${result[key]}"></div>
          <div class="color-info">
            <div class="color-name">${key}</div>
            <div class="color-code">${result[key]}</div>
          </div>
        </div>
      `;
    }
  });

  html += '</div>';
  colorGrid.innerHTML = html;
  resultBox?.classList.remove('hidden');
}

// Typography handlers
const typographyButton = document.getElementById('typographyButton');
typographyButton?.addEventListener('click', async () => {
  const context = (document.getElementById('typographyContext') as HTMLTextAreaElement).value;
  const contentType = (document.getElementById('contentType') as HTMLSelectElement).value;

  if (!context.trim()) {
    showStatus('Please describe the design context', 'error');
    return;
  }

  showStatus('Generating typography suggestions...', 'loading');

  try {
    const response = await fetch(`${MCP_API_BASE}/suggest-typography`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        design_context: context,
        content_type: contentType,
        accessibility: true,
        locale: 'en',
      }),
    });

    if (!response.ok) {
      throw new Error(`Server error: ${response.statusText}`);
    }

    const result = await response.json();
    displayTypography(result);
    showStatus('Typography suggestions generated!', 'success');
  } catch (error) {
    showStatus(`Error: ${(error as Error).message}`, 'error');
  }
});

function displayTypography(result: any) {
  const resultBox = document.getElementById('typographyResult');
  const typographyList = document.getElementById('typographyList');

  if (!typographyList || !result.recommendations) return;

  let html = '';

  result.recommendations.forEach((rec: any) => {
    html += `
      <div class="improvement-item">
        <div class="improvement-title">${rec.name}</div>
        <div class="improvement-detail">
          <strong>Font:</strong> ${rec.font_family}, ${rec.font_size}px, weight ${rec.font_weight}
          <br>
          <strong>Line Height:</strong> ${rec.line_height}
          <br>
          <code style="display: block; margin-top: 4px; font-size: 10px; background: #f5f5f5; padding: 4px; border-radius: 2px;">
            ${rec.css}
          </code>
        </div>
      </div>
    `;
  });

  typographyList.innerHTML = html;
  resultBox?.classList.remove('hidden');
}

// Status messages
function showStatus(message: string, type: 'info' | 'loading' | 'success' | 'error' = 'info') {
  const statusText = document.getElementById('statusText');
  if (!statusText) return;

  statusText.textContent = message;
  statusText.className = type;

  if (type !== 'loading') {
    setTimeout(() => {
      statusText.textContent = 'Ready';
      statusText.className = '';
    }, 3000);
  }
}

// Message listener for plugin communication
window.addEventListener('message', (event) => {
  const message = event.data;
  console.log('UI received message:', message);

  if (message.type === 'plugin-loaded') {
    showStatus('Plugin connected to Penpot', 'success');
  } else if (message.type === 'error') {
    showStatus(`Plugin error: ${message.message}`, 'error');
  }
});

console.log('Penpot AI Design Assistant UI loaded');
showStatus('Ready', 'info');
