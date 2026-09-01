/**
 * Penpot AI Design Assistant Plugin
 * Communicates with MCP Server for design suggestions
 */

penpot.ui.open('AI Design Assistant', 'ui.html', {
  width: 400,
  height: 600,
  resizable: true,
});

/**
 * Message handler - receives requests from UI panel
 */
penpot.ui.onMessage((message) => {
  console.log('Plugin received message:', message);

  switch (message.type) {
    case 'design-suggestion':
      handleDesignSuggestion(message.payload);
      break;

    case 'improve-component':
      handleImproveComponent(message.payload);
      break;

    case 'get-selection':
      sendSelection();
      break;

    default:
      console.warn('Unknown message type:', message.type);
  }
});

/**
 * Handle design suggestion request
 */
async function handleDesignSuggestion(payload: any) {
  try {
    const { designBrief, context } = payload;

    // Call MCP server
    const suggestion = await fetchDesignSuggestion(designBrief, context);

    // Create component on canvas
    const shapes = createComponentFromSuggestion(suggestion);

    // Send success message to UI
    penpot.ui.sendMessage({
      type: 'suggestion-created',
      success: true,
      shapeIds: shapes.map((s) => s.id),
    });
  } catch (error) {
    penpot.ui.sendMessage({
      type: 'error',
      message: (error as Error).message,
    });
  }
}

/**
 * Handle component improvement request
 */
async function handleImproveComponent(payload: any) {
  try {
    const page = penpot.currentPage;
    const selection = page.selection;

    if (selection.length === 0) {
      throw new Error('No component selected. Please select a component to improve.');
    }

    const selectedShape = selection[0];
    const componentName = selectedShape.name;

    // Get current design info
    const currentDesign = extractDesignInfo(selectedShape);

    // Call MCP server
    const improvements = await fetchComponentImprovements(
      componentName,
      currentDesign,
      payload.improvementAreas || []
    );

    // Send improvements to UI for review
    penpot.ui.sendMessage({
      type: 'improvements-generated',
      improvements: improvements,
      componentName: componentName,
    });
  } catch (error) {
    penpot.ui.sendMessage({
      type: 'error',
      message: (error as Error).message,
    });
  }
}

/**
 * Send current selection info to UI
 */
function sendSelection() {
  const page = penpot.currentPage;
  const selection = page.selection;

  if (selection.length === 0) {
    penpot.ui.sendMessage({
      type: 'selection-info',
      selected: false,
    });
    return;
  }

  const selectedShape = selection[0];
  penpot.ui.sendMessage({
    type: 'selection-info',
    selected: true,
    name: selectedShape.name,
    type: selectedShape.type,
    x: selectedShape.x,
    y: selectedShape.y,
    width: selectedShape.width,
    height: selectedShape.height,
  });
}

/**
 * Call MCP server to get design suggestion
 */
async function fetchDesignSuggestion(designBrief: string, context: any) {
  const mcpUrl = 'http://localhost:3000/api/design-suggestion';

  const response = await fetch(mcpUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      design_brief: designBrief,
      context: context,
    }),
  });

  if (!response.ok) {
    throw new Error(`MCP server error: ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Call MCP server to get component improvements
 */
async function fetchComponentImprovements(
  componentName: string,
  currentDesign: string,
  improvementAreas: string[]
) {
  const mcpUrl = 'http://localhost:3000/api/improve-component';

  const response = await fetch(mcpUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      component_name: componentName,
      current_design: currentDesign,
      improvement_areas: improvementAreas,
    }),
  });

  if (!response.ok) {
    throw new Error(`MCP server error: ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Create component shapes from design suggestion
 */
function createComponentFromSuggestion(suggestion: any) {
  const page = penpot.currentPage;
  const shapes: any[] = [];

  // Create main container
  const container = page.createShape('FRAME', {
    name: suggestion.suggestion?.substring(0, 50) || 'AI Generated Component',
    x: 100,
    y: 100,
    width: 200,
    height: 100,
  });

  if (suggestion.properties) {
    // Apply styling
    if (suggestion.properties.colors && suggestion.properties.colors[0]) {
      container.fillColor = suggestion.properties.colors[0];
    }

    if (suggestion.properties.spacing) {
      container.paddingTop = parseInt(suggestion.properties.spacing.padding) || 12;
      container.paddingLeft = parseInt(suggestion.properties.spacing.padding) || 16;
    }
  }

  shapes.push(container);

  return shapes;
}

/**
 * Extract design information from a shape
 */
function extractDesignInfo(shape: any): string {
  const info = {
    width: shape.width,
    height: shape.height,
    fillColor: shape.fillColor,
    strokeColor: shape.strokeColor,
    strokeWidth: shape.strokeWidth,
    opacity: shape.opacity,
  };

  return JSON.stringify(info, null, 2);
}

// Notify that plugin is loaded
console.log('Penpot AI Design Assistant Plugin loaded');
penpot.ui.sendMessage({ type: 'plugin-loaded' });
