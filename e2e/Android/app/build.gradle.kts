import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.moneat.e2e.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moneat.e2e.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        // Read DSN from local.properties
        val localPropertiesFile = rootProject.file("local.properties")
        val sentryDsn = if (localPropertiesFile.exists()) {
            val props = Properties()
            localPropertiesFile.inputStream().use { props.load(it) }
            props.getProperty("sentry.dsn", "")
        } else {
            ""
        }
        manifestPlaceholders["sentryDsn"] = sentryDsn
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.sentry.android)
    implementation(libs.sentry.android.replay)
}
