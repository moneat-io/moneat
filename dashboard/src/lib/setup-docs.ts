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
      description: 'Initialize Sentry at your application entry point. Include replayIntegration for Session Replay (optional).',
      code: `import * as Sentry from "@sentry/browser";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  integrations: [Sentry.replayIntegration()],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
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
      description: 'Initialize Sentry before rendering your React app (main.tsx or index.tsx). Include replayIntegration for Session Replay (optional).',
      code: `import * as Sentry from "@sentry/react";
import App from "./App";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  integrations: [
    Sentry.browserTracingIntegration(),
    Sentry.replayIntegration(),
  ],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
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
  integrations: [
    Sentry.browserTracingIntegration(),
    Sentry.replayIntegration(),
  ],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
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

  java: createDocs('java', 'Sentry Java SDK', [
    {
      title: 'Add the dependency',
      description: 'Add the Sentry Java SDK to your build.',
      code: `// Gradle
implementation("io.sentry:sentry")

// Maven
<dependency>
  <groupId>io.sentry</groupId>
  <artifactId>sentry</artifactId>
</dependency>`,
      language: 'java',
    },
    {
      title: 'Initialize in your app startup',
      description: 'Initialize Sentry as early as possible.',
      code: `import io.sentry.Sentry;

Sentry.init(options -> {
  options.setDsn("${DSN_PLACEHOLDER}");
  options.setTracesSampleRate(1.0);
});`,
      language: 'java',
    },
    {
      title: 'Verify installation',
      description: 'Send a test message.',
      code: `Sentry.captureMessage("Moneat test event from Java");`,
      language: 'java',
    },
  ]),

  'spring-boot': createDocs('spring-boot', 'Sentry Spring Boot Starter', [
    {
      title: 'Add the dependency',
      description: 'Install the Spring Boot starter package.',
      code: `// Gradle
implementation("io.sentry:sentry-spring-boot-starter")

// Maven
<dependency>
  <groupId>io.sentry</groupId>
  <artifactId>sentry-spring-boot-starter</artifactId>
</dependency>`,
      language: 'java',
    },
    {
      title: 'Configure DSN',
      description: 'Set your DSN in Spring configuration.',
      code: `# application.properties
sentry.dsn=${DSN_PLACEHOLDER}
sentry.traces-sample-rate=1.0`,
      language: 'properties',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event from code.',
      code: `import io.sentry.Sentry;

Sentry.captureMessage("Moneat test event from Spring Boot");`,
      language: 'java',
    },
  ]),

  dotnet: createDocs('dotnet', '.NET SDK', [
    {
      title: 'Install the package',
      description: 'Install Sentry for .NET.',
      code: `dotnet add package Sentry`,
      language: 'bash',
    },
    {
      title: 'Initialize in startup',
      description: 'Initialize Sentry before your app handles requests.',
      code: `using Sentry;

SentrySdk.Init(options =>
{
    options.Dsn = "${DSN_PLACEHOLDER}";
    options.TracesSampleRate = 1.0;
});`,
      language: 'csharp',
    },
    {
      title: 'Verify installation',
      description: 'Send a test message.',
      code: `SentrySdk.CaptureMessage("Moneat test event from .NET");`,
      language: 'csharp',
    },
  ]),

  go: createDocs('go', 'sentry-go', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Go SDK.',
      code: `go get github.com/getsentry/sentry-go`,
      language: 'bash',
    },
    {
      title: 'Initialize in main',
      description: 'Initialize Sentry before serving traffic.',
      code: `import "github.com/getsentry/sentry-go"

func main() {
  err := sentry.Init(sentry.ClientOptions{
    Dsn: "${DSN_PLACEHOLDER}",
    TracesSampleRate: 1.0,
  })
  if err != nil {
    panic(err)
  }
  defer sentry.Flush(2 * time.Second)
}`,
      language: 'go',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `sentry.CaptureMessage("Moneat test event from Go")`,
      language: 'go',
    },
  ]),

  ruby: createDocs('ruby', 'sentry-ruby', [
    {
      title: 'Add the gem',
      description: 'Install sentry-ruby using Bundler.',
      code: `# Gemfile
gem "sentry-ruby"`,
      language: 'ruby',
    },
    {
      title: 'Initialize Sentry',
      description: 'Initialize in your app startup.',
      code: `require "sentry-ruby"

Sentry.init do |config|
  config.dsn = "${DSN_PLACEHOLDER}"
  config.traces_sample_rate = 1.0
end`,
      language: 'ruby',
    },
    {
      title: 'Verify installation',
      description: 'Send a test event.',
      code: `Sentry.capture_message("Moneat test event from Ruby")`,
      language: 'ruby',
    },
  ]),

  rails: createDocs('rails', 'sentry-rails', [
    {
      title: 'Add the gem',
      description: 'Install sentry-rails with Bundler.',
      code: `# Gemfile
gem "sentry-rails"`,
      language: 'ruby',
    },
    {
      title: 'Configure DSN',
      description: 'Set your DSN in credentials or environment.',
      code: `# config/initializers/sentry.rb
Sentry.init do |config|
  config.dsn = "${DSN_PLACEHOLDER}"
  config.traces_sample_rate = 1.0
end`,
      language: 'ruby',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `Sentry.capture_message("Moneat test event from Rails")`,
      language: 'ruby',
    },
  ]),

  php: createDocs('php', 'sentry/sentry', [
    {
      title: 'Install with Composer',
      description: 'Add the Sentry PHP SDK to your project.',
      code: `composer require sentry/sentry`,
      language: 'bash',
    },
    {
      title: 'Initialize in bootstrap',
      description: 'Initialize Sentry when the app starts.',
      code: `<?php
require_once '/path/to/vendor/autoload.php';

\\Sentry\\init([
    'dsn' => '${DSN_PLACEHOLDER}',
    'traces_sample_rate' => 1.0,
]);`,
      language: 'php',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `\\Sentry\\captureMessage('Moneat test event from PHP');`,
      language: 'php',
    },
  ]),

  laravel: createDocs('laravel', 'sentry/sentry-laravel', [
    {
      title: 'Install with Composer',
      description: 'Install the Laravel SDK package.',
      code: `composer require sentry/sentry-laravel`,
      language: 'bash',
    },
    {
      title: 'Configure DSN',
      description: 'Set DSN in your environment configuration.',
      code: `# .env
SENTRY_LARAVEL_DSN=${DSN_PLACEHOLDER}
SENTRY_TRACES_SAMPLE_RATE=1.0`,
      language: 'dotenv',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message from your app.',
      code: `\\Sentry\\captureMessage('Moneat test event from Laravel');`,
      language: 'php',
    },
  ]),

  rust: createDocs('rust', 'sentry', [
    {
      title: 'Install the crate',
      description: 'Add the Sentry crate to your project.',
      code: `cargo add sentry`,
      language: 'bash',
    },
    {
      title: 'Initialize in main',
      description: 'Initialize Sentry at startup.',
      code: `fn main() {
    let _guard = sentry::init((
        "${DSN_PLACEHOLDER}",
        sentry::ClientOptions {
            traces_sample_rate: 1.0,
            ..Default::default()
        },
    ));
}`,
      language: 'rust',
    },
    {
      title: 'Verify installation',
      description: 'Send a test message.',
      code: `sentry::capture_message("Moneat test event from Rust", sentry::Level::Info);`,
      language: 'rust',
    },
  ]),

  elixir: createDocs('elixir', 'sentry (Hex)', [
    {
      title: 'Add dependency',
      description: 'Install sentry from Hex.',
      code: `# mix.exs
defp deps do
  [
    {:sentry, "~> 10.0"}
  ]
end`,
      language: 'elixir',
    },
    {
      title: 'Configure Sentry',
      description: 'Add DSN to runtime config.',
      code: `# config/runtime.exs
config :sentry,
  dsn: "${DSN_PLACEHOLDER}",
  environment_name: config_env(),
  enable_source_code_context: true`,
      language: 'elixir',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `Sentry.capture_message("Moneat test event from Elixir")`,
      language: 'elixir',
    },
  ]),

  django: createDocs('django', 'sentry-sdk (Django)', [
    {
      title: 'Install the package',
      description: 'Install sentry-sdk with the Django extra.',
      code: `pip install "sentry-sdk[django]"`,
      language: 'bash',
    },
    {
      title: 'Initialize in settings',
      description: 'Initialize Sentry in your Django settings module.',
      code: `import sentry_sdk
from sentry_sdk.integrations.django import DjangoIntegration

sentry_sdk.init(
    dsn="${DSN_PLACEHOLDER}",
    integrations=[DjangoIntegration()],
    traces_sample_rate=1.0,
)`,
      language: 'python',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `import sentry_sdk

sentry_sdk.capture_message("Moneat test event from Django")`,
      language: 'python',
    },
  ]),

  flask: createDocs('flask', 'sentry-sdk (Flask)', [
    {
      title: 'Install the package',
      description: 'Install sentry-sdk with Flask support.',
      code: `pip install "sentry-sdk[flask]"`,
      language: 'bash',
    },
    {
      title: 'Initialize in app startup',
      description: 'Initialize Sentry before app creation.',
      code: `import sentry_sdk
from sentry_sdk.integrations.flask import FlaskIntegration
from flask import Flask

sentry_sdk.init(
    dsn="${DSN_PLACEHOLDER}",
    integrations=[FlaskIntegration()],
    traces_sample_rate=1.0,
)

app = Flask(__name__)`,
      language: 'python',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `sentry_sdk.capture_message("Moneat test event from Flask")`,
      language: 'python',
    },
  ]),

  fastapi: createDocs('fastapi', 'sentry-sdk (FastAPI)', [
    {
      title: 'Install the package',
      description: 'Install sentry-sdk with FastAPI support.',
      code: `pip install "sentry-sdk[fastapi]"`,
      language: 'bash',
    },
    {
      title: 'Initialize in app startup',
      description: 'Initialize Sentry before creating your FastAPI app.',
      code: `import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from fastapi import FastAPI

sentry_sdk.init(
    dsn="${DSN_PLACEHOLDER}",
    integrations=[FastApiIntegration()],
    traces_sample_rate=1.0,
)

app = FastAPI()`,
      language: 'python',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `sentry_sdk.capture_message("Moneat test event from FastAPI")`,
      language: 'python',
    },
  ]),

  angular: createDocs('angular', '@sentry/angular', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Angular SDK.',
      code: `npm install @sentry/angular

# Or with yarn
yarn add @sentry/angular`,
      language: 'bash',
    },
    {
      title: 'Initialize in main.ts',
      description: 'Initialize Sentry before bootstrapping Angular.',
      code: `import * as Sentry from "@sentry/angular";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `Sentry.captureMessage("Moneat test event from Angular");`,
      language: 'typescript',
    },
  ]),

  svelte: createDocs('svelte', '@sentry/svelte', [
    {
      title: 'Install the package',
      description: 'Install the Sentry Svelte SDK.',
      code: `npm install @sentry/svelte

# Or with yarn
yarn add @sentry/svelte`,
      language: 'bash',
    },
    {
      title: 'Initialize in your app entry',
      description: 'Initialize Sentry in your client entry file.',
      code: `import * as Sentry from "@sentry/svelte";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event.',
      code: `Sentry.captureMessage("Moneat test event from Svelte");`,
      language: 'typescript',
    },
  ]),

  nextjs: createDocs('nextjs', '@sentry/nextjs', [
    {
      title: 'Install the package',
      description: 'Install the Next.js SDK.',
      code: `npm install @sentry/nextjs

# Optional setup wizard
npx @sentry/wizard@latest -i nextjs`,
      language: 'bash',
    },
    {
      title: 'Initialize in config files',
      description: 'Create sentry.client.config and sentry.server.config files.',
      code: `import * as Sentry from "@sentry/nextjs";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Send a test event from client or server code.',
      code: `Sentry.captureMessage("Moneat test event from Next.js");`,
      language: 'typescript',
    },
  ]),

  nuxt: createDocs('nuxt', '@sentry/nuxt', [
    {
      title: 'Install the package',
      description: 'Install the Nuxt SDK package.',
      code: `npm install @sentry/nuxt`,
      language: 'bash',
    },
    {
      title: 'Register the module',
      description: 'Add the Sentry module and DSN in nuxt.config.ts.',
      code: `export default defineNuxtConfig({
  modules: ["@sentry/nuxt/module"],
  sentry: {
    dsn: "${DSN_PLACEHOLDER}",
    tracesSampleRate: 1.0,
  },
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event.',
      code: `import * as Sentry from "@sentry/nuxt";

Sentry.captureMessage("Moneat test event from Nuxt");`,
      language: 'typescript',
    },
  ]),

  remix: createDocs('remix', '@sentry/remix', [
    {
      title: 'Install the package',
      description: 'Install the Remix SDK package.',
      code: `npm install @sentry/remix`,
      language: 'bash',
    },
    {
      title: 'Initialize client and server',
      description: 'Create Sentry init files for browser and server runtimes.',
      code: `import * as Sentry from "@sentry/remix";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event.',
      code: `Sentry.captureMessage("Moneat test event from Remix");`,
      language: 'typescript',
    },
  ]),

  astro: createDocs('astro', '@sentry/astro', [
    {
      title: 'Install the package',
      description: 'Install the Astro SDK package.',
      code: `npm install @sentry/astro`,
      language: 'bash',
    },
    {
      title: 'Register the integration',
      description: 'Add Sentry in your astro.config.mjs or astro.config.ts.',
      code: `import { defineConfig } from "astro/config";
import sentry from "@sentry/astro";

export default defineConfig({
  integrations: [sentry({ dsn: "${DSN_PLACEHOLDER}" })],
});`,
      language: 'javascript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event.',
      code: `import * as Sentry from "@sentry/astro";

Sentry.captureMessage("Moneat test event from Astro");`,
      language: 'javascript',
    },
  ]),

  solid: createDocs('solid', '@sentry/solidstart', [
    {
      title: 'Install the package',
      description: 'Install the SolidStart SDK package.',
      code: `npm install @sentry/solidstart`,
      language: 'bash',
    },
    {
      title: 'Initialize in entry files',
      description: 'Initialize Sentry in SolidStart client and server entry points.',
      code: `import * as Sentry from "@sentry/solidstart";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event.',
      code: `Sentry.captureMessage("Moneat test event from SolidStart");`,
      language: 'typescript',
    },
  ]),

  electron: createDocs('electron', '@sentry/electron', [
    {
      title: 'Install the package',
      description: 'Install Sentry for Electron.',
      code: `npm install @sentry/electron`,
      language: 'bash',
    },
    {
      title: 'Initialize in main process',
      description: 'Initialize Sentry in your Electron main process startup.',
      code: `import * as Sentry from "@sentry/electron/main";

Sentry.init({
  dsn: "${DSN_PLACEHOLDER}",
  tracesSampleRate: 1.0,
});`,
      language: 'typescript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event from main or renderer.',
      code: `Sentry.captureMessage("Moneat test event from Electron");`,
      language: 'typescript',
    },
  ]),

  unity: createDocs('unity', 'Sentry Unity SDK', [
    {
      title: 'Install the SDK',
      description: 'Install the Unity SDK package from the official repository.',
      code: `# Package source:
# https://github.com/getsentry/sentry-unity`,
      language: 'text',
    },
    {
      title: 'Initialize in startup script',
      description: 'Initialize Sentry in a script that runs on startup.',
      code: `using Sentry.Unity;

SentryUnity.Init(options =>
{
    options.Dsn = "${DSN_PLACEHOLDER}";
    options.TracesSampleRate = 1.0f;
});`,
      language: 'csharp',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message from gameplay code.',
      code: `SentrySdk.CaptureMessage("Moneat test event from Unity");`,
      language: 'csharp',
    },
  ]),

  unreal: createDocs('unreal', 'Sentry Unreal Plugin', [
    {
      title: 'Install the plugin',
      description: 'Add the official Unreal plugin to your project.',
      code: `# Plugin source:
# https://github.com/getsentry/sentry-unreal`,
      language: 'text',
    },
    {
      title: 'Configure DSN',
      description: 'Set DSN in plugin settings or project config.',
      code: `[/Script/Sentry.SentrySettings]
Dsn=${DSN_PLACEHOLDER}
bEnableAutoSessionTracking=true`,
      language: 'ini',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test event from C++ or Blueprint.',
      code: `USentrySubsystem::CaptureMessage(TEXT("Moneat test event from Unreal"));`,
      language: 'cpp',
    },
  ]),

  godot: createDocs('godot', 'Sentry Godot Plugin', [
    {
      title: 'Install the plugin',
      description: 'Install the official plugin from the repository.',
      code: `# Plugin source:
# https://github.com/getsentry/sentry-godot`,
      language: 'text',
    },
    {
      title: 'Initialize Sentry',
      description: 'Configure DSN when your game boots.',
      code: `Sentry.init({
  "dsn": "${DSN_PLACEHOLDER}",
  "traces_sample_rate": 1.0
})`,
      language: 'gdscript',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `Sentry.capture_message("Moneat test event from Godot")`,
      language: 'gdscript',
    },
  ]),

  native: createDocs('native', 'sentry-native (C/C++)', [
    {
      title: 'Add sentry-native',
      description: 'Add the official C/C++ SDK to your project.',
      code: `# SDK source:
# https://github.com/getsentry/sentry-native`,
      language: 'text',
    },
    {
      title: 'Initialize at startup',
      description: 'Initialize Sentry with your DSN before app logic starts.',
      code: `#include <sentry.h>

sentry_options_t* options = sentry_options_new();
sentry_options_set_dsn(options, "${DSN_PLACEHOLDER}");
sentry_init(options);`,
      language: 'c',
    },
    {
      title: 'Verify installation',
      description: 'Capture a test message.',
      code: `sentry_capture_event(sentry_value_new_message_event(
  SENTRY_LEVEL_INFO,
  "default",
  "Moneat test event from Native C/C++"
));`,
      language: 'c',
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
  reactnative: 'react-native',
  react_native: 'react-native',
  spring: 'spring-boot',
  springboot: 'spring-boot',
  'spring-boot': 'spring-boot',
  csharp: 'dotnet',
  'c#': 'dotnet',
  aspnet: 'dotnet',
  'asp.net': 'dotnet',
  aspnetcore: 'dotnet',
  'asp.netcore': 'dotnet',
  golang: 'go',
  rb: 'ruby',
  ror: 'rails',
  py: 'python',
  next: 'nextjs',
  'next.js': 'nextjs',
  nuxtjs: 'nuxt',
  'nuxt.js': 'nuxt',
  solidstart: 'solid',
  'solid-start': 'solid',
  'unreal-engine': 'unreal',
  'godot-engine': 'godot',
  c: 'native',
  cpp: 'native',
  'c++': 'native',
}

export function getSetupDocs(platformId?: string, dsn: string = ''): PlatformSetupDocs | null {
  if (!platformId) return setupDocs.other

  const lower = platformId.toLowerCase()
  const normalized = lower.replace(/_/g, '-')
  const resolvedId = platformAliases[lower] ?? platformAliases[normalized] ?? normalized
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
