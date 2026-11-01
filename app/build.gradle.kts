import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.Properties

plugins { alias(libs.plugins.android.application) }

abstract class BuildRustTask @Inject constructor(private val executor: ExecOperations) : DefaultTask() {
    @get:Input abstract val rustRoot: Property<String>
    @get:Input abstract val sdkRoot: Property<String>
    @get:Input abstract val ndkRevision: Property<String>
    @get:Input abstract val releaseBuild: Property<Boolean>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty
    @get:LocalState abstract val cargoDirectory: DirectoryProperty
    @TaskAction fun buildNative() {
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        check(cargo.isFile) { "Install Rust and cargo-ndk; see README.md" }
        val args = mutableListOf(cargo.absolutePath, "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "24",
            "-o", jniDirectory.get().asFile.absolutePath, "build", "--locked", "-p", "vnt-android")
        if (releaseBuild.get()) args += "--release"
        executor.exec {
            workingDir(rustRoot.get())
            commandLine(args)
            environment("ANDROID_HOME", sdkRoot.get())
            environment("ANDROID_NDK_HOME", File(sdkRoot.get(), "ndk/${ndkRevision.get()}").absolutePath)
            environment("CARGO_TARGET_DIR", cargoDirectory.get().asFile.absolutePath)
            environment("CARGO_PROFILE_DEV_OPT_LEVEL", "2")
            environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--no-undefined")
            environment("PATH", "${cargo.parent}:${System.getenv("PATH")}")
        }.assertNormalExitValue()
    }
}
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
val rustDirectory = providers.gradleProperty("vnt.rustDir").orElse(local.getProperty("vnt.rustDir")
    ?: rootProject.file("../../RustroverProjects/vnt-remote").absolutePath)
val androidSdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("Set sdk.dir in local.properties or ANDROID_HOME")
val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val signingValues = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = signingValues.all { it.orNull?.isNotBlank() == true }
check(signingValues.none { it.orNull?.isNotBlank() == true } || hasReleaseSigning) {
    "Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD"
}

android {
    namespace = "com.rustvnt.remote"
    compileSdk { version = release(37) }
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.rustvnt.remote"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStore.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        release {
            optimization { enable = true }
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    splits {
        abi {
            isEnable = providers.gradleProperty("vnt.splitApks").orNull == "true"
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}
androidComponents {
    onVariants { variant ->
        val task = tasks.register<BuildRustTask>("buildRust${variant.name.replaceFirstChar { it.uppercaseChar() }}") {
            rustRoot.set(rustDirectory)
            sdkRoot.set(androidSdk)
            ndkRevision.set("28.2.13676358")
            releaseBuild.set(variant.buildType == "release")
            jniDirectory.set(layout.buildDirectory.dir("generated/rust/${variant.name}/jniLibs"))
            cargoDirectory.set(layout.buildDirectory.dir("rust/${variant.name}"))
            sources.from(provider { fileTree(rustDirectory.get()) {
                include("Cargo.toml", "Cargo.lock", "crates/vnt-client/**", "crates/vnt-link/**", "crates/vnt-android/**",
                    "crates/vnt-proto/**", "crates/vnt-net/**", "crates/vnt-audio/**", "vendor/vnt-core/**", "vendor/opus/**")
                exclude("**/target/**", "**/.git/**")
            } })
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, BuildRustTask::jniDirectory)
    }
}
dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
