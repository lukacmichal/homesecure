plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- spolocny blok pre vsetky appky (nemen len tu, viz docs/shared-standard.md) ----
/**
 * Pristupove udaje k NAS-u sa citaju z JEDNEHO suboru spolocneho pre vsetky
 * appky: nas-credentials.local. Odtial sa pri builde vlozia do APK ako
 * predvolby, takze na telefone sa uz nic nezadava.
 *
 * Prepis v Nastaveniach appky ma prednost — to je cesta pre zmenu hesla bez
 * rebuildu. Ked subor chyba, hodnoty ostanu prazdne a appka si udaje vypyta
 * v Nastaveniach ako predtym; build kvoli tomu nikdy nespadne.
 *
 * Heslo timto konci v APK. Je to vedome rozhodnutie: APK lezia na tom istom
 * NAS-e pod tym istym uctom, takze kto sa dostane k nim, ten ucet uz ma.
 */
fun nasSetting(key: String): String {
    val f = file(System.getenv("NAS_CREDENTIALS") ?: "nas-credentials.local")
    if (!f.exists()) return ""
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")?.trim().orEmpty()
}

/** Heslo moze obsahovat spatne lomitko aj uvodzovku — bez tohto by build nepreslo. */
fun nasField(key: String): String =
    "\"" + nasSetting(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// ---- koniec spolocneho bloku -------------------------------------------------

android {
    namespace = "sk.homesecure"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.homesecure"
        minSdk = 26
        targetSdk = 35
        // 1.6: jednotna paticka s verziou dole (docs/shared-standard.md 2.7).
        // Vydanie 3. 9. 2026: pocitadlo prenesenych dat rozdelene na Wi-Fi
        // a mobilne (Prenos.kt, spolocny subor) a sirsi rozsah velkosti
        // pisma (-6 az +6 namiesto -1 az +3).
        versionCode = 10
        versionName = "1.9"

        buildConfigField("String", "NAS_HOST", nasField("NAS_HOST"))
        buildConfigField("String", "NAS_SHARE", nasField("NAS_SHARE"))
        buildConfigField("String", "NAS_USER", nasField("NAS_USER"))
        buildConfigField("String", "NAS_PASS", nasField("NAS_PASS"))

        buildConfigField("String", "MAIL_HOST", nasField("MAIL_HOST"))
        buildConfigField("String", "MAIL_PORT", nasField("MAIL_PORT"))
        buildConfigField("String", "MAIL_USER", nasField("MAIL_USER"))
        buildConfigField("String", "MAIL_PASS", nasField("MAIL_PASS"))
        buildConfigField("String", "MAIL_TO", nasField("MAIL_TO"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // android-mail aj android-activation nesu vlastnu kopiu tychto suborov
    // a merger si s tym sam neporadi.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }

    // Rovnaka konvencia ako WakeNB: na NAS-e lezi vzdy rovnako pomenovany subor,
    // ktory sa pri novom vydani len prepise.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "homesecure.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Potiahnutie dole = obnov; rovnake gesto vo vsetkych appkach.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Sluzba potrebuje LifecycleOwner, aby sa na nu dala nabindovat CameraX.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Kamera: ImageAnalysis (detekcia pohybu) + ImageCapture (fotka v plnom rozliseni).
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")

    // SMTP odosielanie fotiek (Android port Sun/Jakarta Mail).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Self-update z NAS-u cez SMB2/3 - rovnaka kniznica ako WakeNB.
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // Gmail app password nedavame do obycajnych prefs.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
