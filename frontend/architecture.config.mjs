const architectureConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
  ],
  exceptions: [],
};

export default architectureConfig;
