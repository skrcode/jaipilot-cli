import fs from "node:fs";
import path from "node:path";

function required(name) {
  const value = process.env[name];
  if (!value || !value.trim()) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value.trim();
}

function parseBoolean(name, defaultValue) {
  const raw = process.env[name];
  if (raw == null || raw.trim() === "") {
    return defaultValue;
  }
  const normalized = raw.trim().toLowerCase();
  if (["1", "true", "yes", "y", "on"].includes(normalized)) {
    return true;
  }
  if (["0", "false", "no", "n", "off"].includes(normalized)) {
    return false;
  }
  throw new Error(`Invalid boolean value for ${name}: ${raw}`);
}

function parsePort() {
  const raw = process.env.PORT;
  if (!raw || raw.trim() === "") {
    return 3000;
  }
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed) || parsed <= 0) {
    throw new Error(`Invalid PORT value: ${raw}`);
  }
  return parsed;
}

function parseAppId() {
  const raw = required("GITHUB_APP_ID");
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    throw new Error(`Invalid GITHUB_APP_ID value: ${raw}`);
  }
  return parsed;
}

function parseWebhookPath() {
  const raw = process.env.WEBHOOK_PATH?.trim() || "/api/webhook";
  return raw.startsWith("/") ? raw : `/${raw}`;
}

function resolvePrivateKey() {
  const inline = process.env.GITHUB_APP_PRIVATE_KEY;
  if (inline && inline.trim()) {
    return inline.replace(/\\n/g, "\n");
  }

  const keyPath = process.env.GITHUB_APP_PRIVATE_KEY_PATH;
  if (keyPath && keyPath.trim()) {
    const absolute = path.resolve(keyPath.trim());
    return fs.readFileSync(absolute, "utf8");
  }

  throw new Error("Set either GITHUB_APP_PRIVATE_KEY or GITHUB_APP_PRIVATE_KEY_PATH.");
}

function resolveActionRepo() {
  const configured = process.env.JAIPILOT_ACTION_REPO?.trim();
  if (configured) {
    return configured;
  }
  const inferred = process.env.GITHUB_REPOSITORY?.trim();
  if (inferred) {
    return inferred;
  }
  throw new Error(
    "Missing JAIPILOT_ACTION_REPO. Set it explicitly (for example: JAIPilot/jaipilot-cli)."
  );
}

export const config = {
  appId: parseAppId(),
  webhookSecret: required("GITHUB_WEBHOOK_SECRET"),
  privateKey: resolvePrivateKey(),
  port: parsePort(),
  webhookPath: parseWebhookPath(),
  healthPath: "/healthz",
  actionRepo: resolveActionRepo(),
  actionRef: process.env.JAIPILOT_ACTION_REF?.trim() || "action-v1",
  workflowPath:
    process.env.JAIPILOT_WORKFLOW_PATH?.trim() ||
    ".github/workflows/jaipilot-generate.yml",
  workflowCommitMessage:
    process.env.JAIPILOT_WORKFLOW_COMMIT_MESSAGE?.trim() ||
    "chore: add JAIPilot generate workflow",
  bootstrapOnInstall: parseBoolean("JAIPILOT_BOOTSTRAP_ON_INSTALL", true),
  bootstrapOnPullRequest: parseBoolean("JAIPILOT_BOOTSTRAP_ON_PULL_REQUEST", true),
  dispatchOnInstall: parseBoolean("JAIPILOT_DISPATCH_ON_INSTALL", false),
  dispatchOnPullRequest: parseBoolean("JAIPILOT_DISPATCH_ON_PULL_REQUEST", false)
};
