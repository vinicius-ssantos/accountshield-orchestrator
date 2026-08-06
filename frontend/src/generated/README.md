# Generated frontend contracts

Files below this directory are deterministic build artifacts.

Do not edit them manually. Update `openapi/accountshield.openapi.json` and run:

```bash
npm run openapi:generate
npm run openapi:check
```

The current generator is `accountshield-openapi-generator@1.0.0`, implemented in `scripts/generate-openapi-client.mjs` with Node.js built-ins only.

Application pages, feature modules, and design-system components must not import this directory directly. Consume generated contracts through handwritten adapters under `src/server/bff`. ESLint enforces this boundary.

Generated artifacts must not contain:

- environment-specific server URLs;
- credentials, tokens, cookies, or authorization values;
- browser storage logic;
- feature presentation logic;
- raw backend Problem Details forwarding.
