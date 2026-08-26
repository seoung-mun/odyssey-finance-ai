import { defineConfig } from "@playwright/test";

const baseURL = process.env.E2E_BASE_URL;
if (!baseURL) throw new Error("E2E_BASE_URL must be the Caddy HTTPS origin");
if (new URL(baseURL).protocol !== "https:") throw new Error("E2E_BASE_URL must use HTTPS");
const spki = process.env.E2E_TLS_SPKI;
if (
  spki !== undefined &&
  (!spki || !spki.split(",").every((pin) => /^[A-Za-z0-9+/]{43}=$/.test(pin)))
)
  throw new Error("E2E_TLS_SPKI must be a comma-separated SHA-256 SPKI list");

export default defineConfig({
  testDir: "./e2e-real",
  timeout: 90_000,
  workers: 1,
  preserveOutput: "never",
  use: {
    baseURL,
    launchOptions: {
      args: spki ? [`--ignore-certificate-errors-spki-list=${spki}`] : [],
    },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
