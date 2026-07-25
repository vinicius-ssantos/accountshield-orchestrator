import type { NextConfig } from "next";

import { readFrontendEnvironment } from "./src/config/environment";
import { createStaticSecurityHeaders } from "./src/security/headers";

const environment = readFrontendEnvironment(process.env, "build");

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: createStaticSecurityHeaders(environment.appEnvironment),
      },
    ];
  },
};

export default nextConfig;
