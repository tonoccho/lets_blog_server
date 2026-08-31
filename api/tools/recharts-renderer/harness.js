// recharts-renderer harness: React + Recharts をヘッドレスブラウザ(Playwright)内で1回だけレンダリングし、
// 結果のSVGを静的な文字列として取り出すためのハーネス。esbuildでReact/ReactDOM/Rechartsごとバンドルする。
// 出力はAPIサーバー(RechartsRenderer.java)がPlaywright経由で読み込み、[recharts]タグの静的HTML/SVG化に使う。
// 最終的な公開記事・プレビューには、このバンドル自体は一切配信されない(サーバー内部のレンダリングにのみ使う)。
const React = require('react');
const { createRoot } = require('react-dom/client');
const {
  BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Legend,
} = require('recharts');

const DEFAULT_PALETTE = [
  '#4e79a7', '#f28e2b', '#e15759', '#76b7b2', '#59a14f',
  '#edc948', '#b07aa1', '#ff9da7', '#9c755f', '#bab0ac',
];

function colorAt(colors, i) {
  return colors[i % colors.length];
}

function renderAxes(config) {
  const nodes = [];
  if (config.xAxisKey) {
    nodes.push(React.createElement(XAxis, { key: 'x', dataKey: config.xAxisKey, stroke: config.textColor }));
  }
  const yAxisProps = { key: 'y', stroke: config.textColor };
  if (config.yAxisLabel) {
    yAxisProps.label = { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fill: config.textColor };
  }
  nodes.push(React.createElement(YAxis, yAxisProps));
  return nodes;
}

function buildChartElement(config) {
  const { type, data, xAxisKey, seriesKeys, colors, stacked, width, height, gridColor } = config;
  const commonProps = { width, height, data };
  const grid = React.createElement(CartesianGrid, { key: 'grid', strokeDasharray: '3 3', stroke: gridColor });
  const legend = seriesKeys.length > 1
    ? React.createElement(Legend, { key: 'legend' })
    : null;

  if (type === 'pie') {
    const [nameKey, valueKey] = seriesKeys;
    const pie = React.createElement(
      Pie,
      {
        data,
        dataKey: valueKey,
        nameKey,
        cx: '50%',
        cy: '50%',
        outerRadius: Math.min(width, height) / 2 - 40,
        isAnimationActive: false,
        label: true,
      },
      data.map((_, i) => React.createElement(Cell, { key: `cell-${i}`, fill: colorAt(colors, i) }))
    );
    return React.createElement(PieChart, { width, height }, pie, legend);
  }

  const axes = renderAxes({ xAxisKey, textColor: config.textColor, yAxisLabel: config.yAxisLabel });

  if (type === 'bar') {
    const bars = seriesKeys.map((key, i) => React.createElement(Bar, {
      key,
      dataKey: key,
      fill: colorAt(colors, i),
      isAnimationActive: false,
      stackId: stacked ? 'stack' : undefined,
    }));
    return React.createElement(BarChart, commonProps, grid, ...axes, legend, ...bars);
  }

  if (type === 'area') {
    const areas = seriesKeys.map((key, i) => React.createElement(Area, {
      key,
      type: 'monotone',
      dataKey: key,
      stroke: colorAt(colors, i),
      fill: colorAt(colors, i),
      fillOpacity: 0.4,
      isAnimationActive: false,
      stackId: stacked ? 'stack' : undefined,
    }));
    return React.createElement(AreaChart, commonProps, grid, ...axes, legend, ...areas);
  }

  // line (default)
  const lines = seriesKeys.map((key, i) => React.createElement(Line, {
    key,
    type: 'monotone',
    dataKey: key,
    stroke: colorAt(colors, i),
    isAnimationActive: false,
  }));
  return React.createElement(LineChart, commonProps, grid, ...axes, legend, ...lines);
}

/**
 * @param {object} config { type, data, xAxisKey, seriesKeys, colors, stacked, width, height, textColor, gridColor }
 * window.__chartResult / window.__chartError にJava(Playwright)側からポーリング可能な形で結果を書き込む。
 */
window.renderChart = function renderChart(config) {
  try {
    const colors = (config.colors && config.colors.length > 0) ? config.colors : DEFAULT_PALETTE;
    const element = buildChartElement(Object.assign({}, config, { colors }));
    const container = document.getElementById('root');
    const root = createRoot(container);
    root.render(element);
    // Rechartsはmount後、レイアウト計算(ResizeObserver不要な固定幅/高さ指定時は同期に近い)を経てSVGを
    // 生成する。念のため次のフレームまで待ってから読み取ることで、SVG要素が確実に存在する状態にする。
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const wrapper = container.querySelector('.recharts-wrapper');
        if (!wrapper) {
          window.__chartError = 'チャートの生成に失敗しました';
          return;
        }
        window.__chartResult = wrapper.outerHTML;
      });
    });
  } catch (e) {
    window.__chartError = (e && e.message) ? e.message : String(e);
  }
};
