// Moneat - observability platform
// Copyright (C) 2026 Moneat
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Platform-specific setup docs for Docusaurus.
 * Uses YOUR_DSN_HERE, your-org-slug, your-project-slug as placeholders.
 * Ported from dashboard/src/lib/setup-docs.ts
 */

export interface SetupStep {
  title: string;
  description: string;
  code?: string;
  language?: string;
}

export interface PlatformSetupDocs {
  platformId: string;
  sdkName: string;
  steps: SetupStep[];
}

const DSN = 'YOUR_DSN_HERE';

function createDocs(platformId: string, sdkName: string, steps: SetupStep[]): PlatformSetupDocs {
  return {platformId, sdkName, steps};
}

export const sdkSetupData: Record<string, PlatformSetupDocs> = {
  android: createDocs('android', 'Sentry Android SDK', [
    {title: 'Add the dependency', description: 'Add the Sentry Android SDK to your app/build.gradle.kts', code: `dependencies {
  implementation("io.sentry:sentry-android:7.0.0")
}`, language: 'kotlin'},
    {title: 'Initialize in Application', description: 'Initialize Sentry as early as possible in your Application class.', code: `import io.sentry.android.core.SentryAndroid

SentryAndroid.init(this) { options ->
    options.dsn = "${DSN}"
    options.tracesSampleRate = 1.0
}`, language: 'kotlin'},
    {title: 'Verify installation', description: 'Trigger a test event to confirm errors are being sent.', code: `Sentry.captureMessage("Moneat test event from Android")`, language: 'kotlin'},
  ]),
  ios: createDocs('ios', 'Sentry Cocoa SDK', [
    {title: 'Add the dependency', description: 'Add Sentry via Swift Package Manager or CocoaPods.', code: `pod 'Sentry', '~> 8.0'`, language: 'ruby'},
    {title: 'Initialize in AppDelegate', description: 'Initialize Sentry before any other setup.', code: `import Sentry

SentrySDK.start { options in
    options.dsn = "${DSN}"
    options.tracesSampleRate = 1.0
}`, language: 'swift'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `SentrySDK.capture(message: "Moneat test event from iOS")`, language: 'swift'},
  ]),
  react: createDocs('react', '@sentry/react', [
    {title: 'Install the package', description: 'Install the Sentry React SDK.', code: `npm install @sentry/react`, language: 'bash'},
    {title: 'Initialize in entry point', description: 'Initialize Sentry before rendering your React app.', code: `import * as Sentry from "@sentry/react";

Sentry.init({
  dsn: "${DSN}",
  integrations: [
    Sentry.browserTracingIntegration(),
    Sentry.replayIntegration(),
  ],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
});`, language: 'typescript'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `Sentry.captureMessage("Moneat test event from React");`, language: 'typescript'},
  ]),
  node: createDocs('node', '@sentry/node', [
    {title: 'Install the package', description: 'Install the Sentry Node SDK.', code: `npm install @sentry/node`, language: 'bash'},
    {title: 'Initialize at the top', description: 'Initialize Sentry before any other imports.', code: `import * as Sentry from "@sentry/node";

Sentry.init({
  dsn: "${DSN}",
  tracesSampleRate: 1.0,
});`, language: 'typescript'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `Sentry.captureMessage("Moneat test event from Node.js");`, language: 'typescript'},
  ]),
  python: createDocs('python', 'sentry-sdk', [
    {title: 'Install the package', description: 'Install the Sentry Python SDK.', code: `pip install sentry-sdk`, language: 'bash'},
    {title: 'Initialize in your app', description: 'Initialize Sentry as early as possible.', code: `import sentry_sdk

sentry_sdk.init(
    dsn="${DSN}",
    traces_sample_rate=1.0,
)`, language: 'python'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `sentry_sdk.capture_message("Moneat test event from Python")`, language: 'python'},
  ]),
  go: createDocs('go', 'sentry-go', [
    {title: 'Install the package', description: 'Install the Sentry Go SDK.', code: `go get github.com/getsentry/sentry-go`, language: 'bash'},
    {title: 'Initialize in main', description: 'Initialize Sentry before serving traffic.', code: `import "github.com/getsentry/sentry-go"

sentry.Init(sentry.ClientOptions{
  Dsn: "${DSN}",
  TracesSampleRate: 1.0,
})
defer sentry.Flush(2 * time.Second)`, language: 'go'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `sentry.CaptureMessage("Moneat test event from Go")`, language: 'go'},
  ]),
  web: createDocs('web', '@sentry/browser', [
    {title: 'Install the package', description: 'Install the Sentry browser SDK.', code: `npm install @sentry/browser`, language: 'bash'},
    {title: 'Initialize early', description: 'Initialize at your application entry point.', code: `import * as Sentry from "@sentry/browser";

Sentry.init({
  dsn: "${DSN}",
  tracesSampleRate: 1.0,
});`, language: 'javascript'},
    {title: 'Verify installation', description: 'Capture a test message.', code: `Sentry.captureMessage("Moneat test event from web");`, language: 'javascript'},
  ]),
  vue: createDocs('vue', '@sentry/vue', [
    {title: 'Install the package', description: 'Install the Sentry Vue SDK.', code: `npm install @sentry/vue`, language: 'bash'},
    {title: 'Initialize when creating app', description: 'Initialize Sentry when creating your Vue app.', code: `import * as Sentry from "@sentry/vue";

Sentry.init({
  app,
  dsn: "${DSN}",
  tracesSampleRate: 1.0,
});`, language: 'javascript'},
  ]),
  nextjs: createDocs('nextjs', '@sentry/nextjs', [
    {title: 'Install the package', description: 'Install the Next.js SDK.', code: `npm install @sentry/nextjs`, language: 'bash'},
    {title: 'Initialize in config', description: 'Create sentry.client.config and sentry.server.config.', code: `import * as Sentry from "@sentry/nextjs";

Sentry.init({
  dsn: "${DSN}",
  tracesSampleRate: 1.0,
});`, language: 'typescript'},
  ]),
  flutter: createDocs('flutter', 'sentry_flutter', [
    {title: 'Add the dependency', description: 'Add sentry_flutter to pubspec.yaml.', code: `dependencies:
  sentry_flutter: ^8.0.0`, language: 'yaml'},
    {title: 'Initialize in main.dart', description: 'Wrap your app with SentryFlutter.init.', code: `import 'package:sentry_flutter/sentry_flutter.dart';

await SentryFlutter.init(
  (options) {
    options.dsn = "${DSN}";
    options.tracesSampleRate = 1.0;
  },
  appRunner: () => runApp(const MyApp()),
);`, language: 'dart'},
  ]),
  'react-native': createDocs('react-native', '@sentry/react-native', [
    {title: 'Install the package', description: 'Install the React Native SDK.', code: `npm install @sentry/react-native`, language: 'bash'},
    {title: 'Initialize in app entry', description: 'Initialize as early as possible.', code: `import * as Sentry from "@sentry/react-native";

Sentry.init({
  dsn: "${DSN}",
  tracesSampleRate: 1.0,
});`, language: 'javascript'},
  ]),
  dotnet: createDocs('dotnet', '.NET SDK', [
    {title: 'Install the package', description: 'Install Sentry for .NET.', code: `dotnet add package Sentry`, language: 'bash'},
    {title: 'Initialize in startup', description: 'Initialize before your app handles requests.', code: `using Sentry;

SentrySdk.Init(options => {
    options.Dsn = "${DSN}";
    options.TracesSampleRate = 1.0;
});`, language: 'csharp'},
  ]),
  other: createDocs('other', 'HTTP API', [
    {title: 'DSN format', description: 'Moneat uses the Sentry envelope protocol. Your DSN format:', code: `https://<public_key>@api.moneat.io/api/<project_id>`, language: 'text'},
    {title: 'Documentation', description: 'See the [Sentry SDK documentation](https://docs.sentry.io/platforms/) for your language. Replace the DSN with your Moneat DSN.', code: `Sentry.init(dsn="${DSN}")`, language: 'text'},
  ]),
};

export const platformOrder = [
  'react', 'node', 'python', 'web', 'vue', 'nextjs',
  'android', 'ios', 'flutter', 'react-native',
  'go', 'dotnet',
  'other',
];
