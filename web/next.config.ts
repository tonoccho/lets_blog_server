import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  experimental: {
    serverActions: {
      bodySizeLimit: "500mb",
    },
    // proxy.ts(middleware)を経由するリクエストのボディサイズ上限。
    // デフォルト10MBのため、zipアップロードがここで切り詰められて
    // Server Action側で "Unexpected end of form" になっていた。
    proxyClientMaxBodySize: "500mb",
  },
};

export default nextConfig;
