module.exports = {
  api: {
    input: {
      target: './openapi.json',
    },
    output: {
      target: './sdk/api-client/src/generated',
      client: 'fetch',
      mode: 'tags-split',
      baseUrl: 'http://localhost:8080',
      prettier: true,
      httpClient: 'fetch',
    },
  },
};
