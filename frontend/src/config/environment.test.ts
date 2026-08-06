import { describe, expect, it } from "vitest";

import { readFrontendEnvironment } from "./environment";

describe("readFrontendEnvironment", () => {
  it("defaults local builds to explicit synthetic fixtures", () => {
    expect(readFrontendEnvironment({}, "build")).toEqual({
      appEnvironment: "local",
      dataSource: "fixtures",
      apiUrl: undefined,
      productionLike: false,
    });
  });

  it("forbids fixture mode in preview and production", () => {
    expect(() =>
      readFrontendEnvironment(
        {
          NEXT_PUBLIC_APP_ENV: "production",
          ACCOUNTSHIELD_DATA_SOURCE: "fixtures",
        },
        "build",
      ),
    ).toThrow("production deployments must use ACCOUNTSHIELD_DATA_SOURCE=live");
  });

  it("allows a live image build without embedding the server-only API origin", () => {
    expect(
      readFrontendEnvironment(
        {
          NEXT_PUBLIC_APP_ENV: "production",
          ACCOUNTSHIELD_DATA_SOURCE: "live",
        },
        "build",
      ),
    ).toMatchObject({
      appEnvironment: "production",
      dataSource: "live",
      apiUrl: undefined,
      productionLike: true,
    });
  });

  it("requires the server-only API origin when live runtime starts", () => {
    expect(() =>
      readFrontendEnvironment({ ACCOUNTSHIELD_DATA_SOURCE: "live" }),
    ).toThrow("Live data mode requires the server-only ACCOUNTSHIELD_API_URL");
  });

  it("rejects browser-visible backend origins and secret-bearing variables", () => {
    expect(() =>
      readFrontendEnvironment({
        NEXT_PUBLIC_ACCOUNTSHIELD_API_URL: "https://api.example.test",
      }),
    ).toThrow("backend origin is server-only");

    expect(() =>
      readFrontendEnvironment({ NEXT_PUBLIC_SESSION_SECRET: "not-public" }),
    ).toThrow("cannot use the NEXT_PUBLIC_ prefix");
  });

  it("accepts only a credential-free API origin", () => {
    expect(() =>
      readFrontendEnvironment({
        ACCOUNTSHIELD_DATA_SOURCE: "live",
        ACCOUNTSHIELD_API_URL: "https://user:pass@api.example.test",
      }),
    ).toThrow("must not contain credentials");

    expect(() =>
      readFrontendEnvironment({
        ACCOUNTSHIELD_DATA_SOURCE: "live",
        ACCOUNTSHIELD_API_URL: "https://api.example.test/v1",
      }),
    ).toThrow("without path, query, or fragment");
  });

  it("rejects loopback API origins in production", () => {
    expect(() =>
      readFrontendEnvironment({
        NEXT_PUBLIC_APP_ENV: "production",
        ACCOUNTSHIELD_DATA_SOURCE: "live",
        ACCOUNTSHIELD_API_URL: "http://127.0.0.1:8080",
      }),
    ).toThrow("must not resolve to a loopback host");
  });

  it("prevents a fixture-built image from masquerading as live", () => {
    expect(() =>
      readFrontendEnvironment({
        NEXT_PUBLIC_APP_ENV: "production",
        ACCOUNTSHIELD_DATA_SOURCE: "live",
        ACCOUNTSHIELD_API_URL: "https://api.example.test",
        ACCOUNTSHIELD_BUILD_APP_ENV: "ci",
        ACCOUNTSHIELD_BUILD_DATA_SOURCE: "fixtures",
      }),
    ).toThrow("does not match image build environment");
  });
});
