/**
 * Platform-specific setup documentation for Moneat SDK integration.
 * Uses {{DSN}} as placeholder for the project's DSN - replace when rendering.
 */

export interface SetupStep {
  title: string
  description: string
  code?: string
  language?: string
}

export interface PlatformSetupDocs {
  platformId: string
  sdkName: string
  steps: SetupStep[]
}

const DSN_PLACEHOLDER = '{{DSN}}'

function createDocs(platformId: string, sdkName: string, steps: SetupStep[]): PlatformSetupDocs {
  return { platformId, sdkName, steps }
}

export const setupDocs: Record<string, PlatformSetupDocs> = {
  android: createDocs('android', 'Sentry Android SDK', [
    {
      title: 'Add the dependency',
      description: 'Add the Sentry Android SDK to your app/build.gradle.kts or build.gradle',
      code: `dependencies {
  implementation("io.sentry:sentry-android:7.0.0")
}`,
      language: 'kotlin',
    },
    {
      title: 'Initialize in your Application class',
      description: 'Create or update your Application class to initialize Sentry as early as possible.',
      code: `import android.app.Application
import io.sentry.android.core.SentryAndroid

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SentryAndroid.init(this) { options ->
            options.dsn = "${DSN_PLACEHOLDER}"
            options.tracesSampleRate = 1.0
            options.isDebug = BuildConfig.DEBUG
        }
    }
}`,
      language: 'kotlin',
    },
    {
      title: 'Register your Application',
      description: 'Add your Application class to AndroidManifest.xml if not already present.',
      code: `<!-- AndroidManifest.xml -->
<application
    android:name=".MyApplication"
    ...>`,
      language: 'xml',
    },
    {
      title: 'Verify installation',
      description: 'Trigger a test event to confirm errors are being sent to Moneat.',
      code: `import io.sentry.Sentry

// Capture a test message
Sentry.captureMessage("Moneat test event from Android")`,
      language: 'kotlin',
    },
  ]),

  ios: createDocs('ios', 'Sentry Cocoa SDK', [
    {
      title: 'Add the dependency',
      description: 'Add Sentry via Swift Package Manager or CocoaPods.',
      code: `# Swift Package Manager - Add to your project:
# https://github.com/getsentry/sentry-cocoa

# Or via CocoaPods:
pod 'Sentry', '~> 8.0'`,
      language: 'ruby',
    },
    {
      title: 'Initialize in AppDelegate',
      description: 'Initialize Sentry in your AppDelegate before any other setup.',
      code: `import Sentry

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        SentrySDK.start { options in
            options.dsn = "${DSN_PLACEHOLDER}"
            options.tracesSampleRate = 1.0
            options.debug = true
        }
        return true
    }
}`,
      language: 'swift',
    },
    {
      title: 'SwiftUI apps',
      description: 'For SwiftUI apps, initialize in your @main App struct.',
      code: `import Sentry
import SwiftUI

@main
struct MyApp: App {
    init() {
        SentrySDK.start { options in
            options.dsn = "${DSN_PLACEHOLDER}"
        }
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}`,
      language: 'swift',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import Sentry

SentrySDK.capture(message: "Moneat test event from iOS")`,
      language: 'swift',
    },
  ]),

  kmp: createDocs('kmp', 'Sentry Kotlin Multiplatform', [
    {
      title: 'Add the dependency',
      description: 'Add Sentry KMP to your shared module build.gradle.kts.',
      code: `// build.gradle.kts (shared module)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.sentry:sentry-kotlin-multiplatform:4.0.0")
        }
        androidMain.dependencies {
            implementation("io.sentry:sentry-android:7.0.0")
        }
    }
}`,
      language: 'kotlin',
    },
    {
      title: 'Initialize on Android',
      description: 'Initialize Sentry in your Android Application class.',
      code: `// androidMain
import io.sentry.android.core.SentryAndroid

SentryAndroid.init(context) { options ->
    options.dsn = "${DSN_PLACEHOLDER}"
}`,
      language: 'kotlin',
    },
    {
      title: 'Initialize on iOS',
      description: 'Initialize Sentry in your iOS MainViewController or App delegate.',
      code: `// iosMain - use CocoaPods/SPM Sentry for iOS
// See iOS setup guide for Swift initialization
// The DSN for your project: ${DSN_PLACEHOLDER}`,
      language: 'kotlin',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event from shared code.',
      code: `import io.sentry.Sentry

Sentry.captureMessage("Moneat test event from KMP")`,
      language: 'kotlin',
    },
  ]),

  'react-native': createDocs('react-native', '@sentry/react-native', [
    {
      title: 'Install the package',
      description: 'Install the Sentry React Native SDK.',
      code: `npm install @sentry/react-native

# Or with yarn
yarn add @sentry/react-native`,
      language: 'bash',
    },
    {
      title: 'Initialize in your app',
      description: 'Initialize Sentry as early as possible in your app entry point (App.js or index.js).',
      code: `import * as Sentry from "@sentry/react-native";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
  debug: __DEV__,
});`,
      language: 'javascript',
    },
    {
      title: 'Wrap your app (optional)',
      description: 'Wrap your root component for better error boundary support.',
      code: `import * as Sentry from "@sentry/react-native";

export default Sentry.wrap(App);`,
      language: 'javascript',
    },
    {
      title: 'Verify installation',
      description: 'Send a test event to confirm setup.',
      code: `import * as Sentry from "@sentry/react-native";

Sentry.captureMessage("Moneat test event from React Native");`,
      language: 'javascript',
    },
  ]),

  flutter: createDocs('flutter', 'sentry_flutter', [
    {
      title: 'Add the dependency',
      description: 'Add sentry_flutter to your pubspec.yaml.',
      code: `dependencies:
  sentry_flutter: ^8.0.0`,
      language: 'yaml',
    },
    {
      title: 'Initialize in main.dart',
      description: 'Wrap your app with SentryFlutter.init and pass your DSN.',
      code: `import 'package:sentry_flutter/sentry_flutter.dart';

Future<void> main() async {
  await SentryFlutter.init(
    (options) {
      options.dsn = "${DSN_PLACEHOLDER}";
      options.tracesSampleRate = 1.0;
    },
    appRunner: () => runApp(const MyApp()),
  );
}`,
      language: 'dart',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import 'package:sentry_flutter/sentry_flutter.dart';

Sentry.captureMessage('Moneat test event from Flutter');`,
      language: 'dart',
    },
  ]),

  web: createDocs('web', '@sentry/browser', [
    {
      title: 'Install the package',
      description: 'Install the Sentry browser SDK.',
      code: `npm install @sentry/browser

# Or with yarn
yarn add @sentry/browser`,
      language: 'bash',
    },
    {
      title: 'Initialize as early as possible',
      description: 'Initialize Sentry at your application entry point (e.g., main.js or index.js).',
      code: `import * as Sentry from "@sentry/browser";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'javascript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message in the browser console.',
      code: `import * as Sentry from "@sentry/browser";

Sentry.captureMessage("Moneat test event from web");`,
      language: 'javascript',
    },
  ]),

  react: createDocs('react', '@sentry/react', [
    {
      title: 'Install the package',
      description: 'Install the Sentry React SDK.',
      code: `npm install @sentry/react

# Or with yarn
yarn add @sentry/react`,
      language: 'bash',
    },
    {
      title: 'Initialize in your entry point',
      description: 'Initialize Sentry before rendering your React app (main.tsx or index.tsx).',
      code: `import * as Sentry from "@sentry/react";
import App from "./App";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  integrations: [Sentry.browserTracingIntegration()],
  tracesSampleRate: 1.0,
});

const root = createRoot(document.getElementById("root")!);
root.render(<App />);`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import * as Sentry from "@sentry/react";

Sentry.captureMessage("Moneat test event from React");`,
      language: 'typescript',
    },
  ]),

  vue: createDocs('vue', '@sentry/vue', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Vue SDK.',
      code: `npm install @sentry/vue

# Or with yarn
yarn add @sentry/vue`,
      language: 'bash',
    },
    {
      title: 'Initialize in main.js',
      description: 'Initialize Sentry when creating your Vue app.',
      code: `import { createApp } from "vue";
import App from "./App.vue";
import * as Sentry from "@sentry/vue";

const app = createApp(App);

Sentry.init({
  app,
  dsn: "${DSN_PLACEHOLDER}",
  integrations: [Sentry.browserTracingIntegration()],
  tracesSampleRate: 1.0,
});

app.mount("#app");`,
      language: 'javascript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import * as Sentry from "@sentry/vue";

Sentry.captureMessage("Moneat test event from Vue");`,
      language: 'javascript',
    },
  ]),

  node: createDocs('node', '@sentry/node', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Node SDK.',
      code: `npm install @sentry/node

# Or with yarn
yarn add @sentry/node`,
      language: 'bash',
    },
    {
      title: 'Initialize at the top of your entry file',
      description: 'Initialize Sentry before any other imports or application code.',
      code: `import * as Sentry from "@sentry/node";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});

// Your app code below
import express from "express";
// ...`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import * as Sentry from "@sentry/node";

Sentry.captureMessage("Moneat test event from Node.js");`,
      language: 'typescript',
    },
  ]),

  python: createDocs('python', 'sentry-sdk', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Python SDK.',
      code: `pip install sentry-sdk`,
      language: 'bash',
    },
    {
      title: 'Initialize in your application',
      description: 'Initialize Sentry as early as possible in your app (e.g., main.py or app initialization).',
      code: `import sentry_sdk

sentry_sdk.init(
    dsn="${DSN_PLACEHOLDER}",
    traces_sample_rate=1.0,
)`,
      language: 'python',
    },
    {
      title: 'Flask example',
      description: 'For Flask apps, init before creating the app.',
      code: `import sentry_sdk
from flask import Flask

sentry_sdk.init(dsn="${DSN_PLACEHOLDER}")

app = Flask(__name__)`,
      language: 'python',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message to verify events reach Moneat.',
      code: `import sentry_sdk

sentry_sdk.capture_message("Moneat test event from Python")`,
      language: 'python',
    },
  ]),

  other: createDocs('other', 'HTTP API', [
    {
      title: 'Moneat uses the Sentry envelope protocol',
      description: 'Send events via POST to your project DSN endpoint. The DSN format is:',
      code: `http://PUBLIC_KEY@your-moneat-host/PROJECT_ID`,
      language: 'text',
    },
    {
      title: 'Your project DSN',
      description: 'Use this DSN when constructing requests to the Moneat ingest API.',
      code: DSN_PLACEHOLDER,
      language: 'text',
    },
    {
      title: 'Envelope format',
      description: 'Events are sent as ndjson (newline-delimited JSON) in Sentry envelope format.',
      code: `POST /api/{project_id}/envelope/
Content-Type: application/x-sentry-envelope

{"event_id":"...","dsn":"${DSN_PLACEHOLDER}"}
{"type":"event","length":...}

{"message":"..."}`,
      language: 'text',
    },
    {
      title: 'Documentation',
      description: 'See Sentry SDK documentation for your language - Moneat is Sentry-compatible. Most Sentry SDKs work with Moneat by simply changing the DSN.',
      code: `# Example: Any Sentry SDK
Sentry.init(dsn="${DSN_PLACEHOLDER}")`,
      language: 'text',
    },
  ]),
}

const platformAliases: Record<string, string> = {
  kotlin: 'kmp',
  'kotlin-multiplatform': 'kmp',
  kotlinmultiplatform: 'kmp',
  javascript: 'web',
  js: 'web',
  typescript: 'web',
  ts: 'web',
}

export function getSetupDocs(platformId?: string, dsn: string = ''): PlatformSetupDocs | null {
  if (!platformId) return setupDocs.other

  const lower = platformId.toLowerCase()
  const resolvedId = platformAliases[lower] ?? lower
  const docs = setupDocs[resolvedId] ?? setupDocs.other

  // Replace DSN placeholder in all code snippets
  return {
    ...docs,
    steps: docs.steps.map((step) => ({
      ...step,
      code: step.code?.split(DSN_PLACEHOLDER).join(dsn),
    })),
  }
}
