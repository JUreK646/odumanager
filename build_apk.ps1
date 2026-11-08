$ErrorActionPreference = "Stop"

$SDK = "D:\AndroidTools\android-sdk"
$BUILD_TOOLS = "$SDK\build-tools\34.0.0"
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$ADB = "$SDK\platform-tools\adb.exe"
$JDK = "D:\AndroidTools\jdk-17"
$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$BUILD_TOOLS;$SDK\platform-tools;$env:PATH"

$ROOT = "d:\odumanager"
$ANDROID_DIR = "$ROOT\android"
$BUILD_DIR = "$ROOT\build"

if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\obj" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\dex" | Out-Null

# Prepare launcher icon (Variant A) and asset image
$mipmaps = @("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi")
foreach ($m in $mipmaps) {
    $dir = "$ANDROID_DIR\res\$m"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item -Force "$ROOT\assets\icon.png" "$dir\ic_launcher.png"
}

$studioPhoto = "C:\Users\Jurek\.gemini\antigravity\brain\e3bd3e07-61c3-4803-b421-6b2b4881838c\scratch\studio_odu200_clean.png"
if (Test-Path $studioPhoto) {
    Copy-Item -Force $studioPhoto "$ANDROID_DIR\assets\odu200.png"
} else {
    Copy-Item -Force "$ROOT\assets\icon.png" "$ANDROID_DIR\assets\odu200.png"
}

Write-Host "1. Compiling Android resources with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" compile --dir "$ANDROID_DIR\res" -o "$BUILD_DIR\res.zip"

Write-Host "2. Linking resources and AndroidManifest.xml..."
& "$BUILD_TOOLS\aapt2.exe" link `
    -I "$PLATFORM" `
    --manifest "$ANDROID_DIR\AndroidManifest.xml" `
    -A "$ANDROID_DIR\assets" `
    --java "$BUILD_DIR\gen" `
    -o "$BUILD_DIR\unaligned.apk" `
    "$BUILD_DIR\res.zip"

Write-Host "3. Compiling Java sources with JDK 17..."
$javaFiles = @(
    "$ANDROID_DIR\src\pl\odumanager\app\MainActivity.java",
    "$BUILD_DIR\gen\pl\odumanager\app\R.java"
)
& "$JDK\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$PLATFORM" -d "$BUILD_DIR\obj" $javaFiles

Write-Host "4. Converting bytecode to DEX with d8..."
$classFiles = Get-ChildItem -Path "$BUILD_DIR\obj" -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName
& "$BUILD_TOOLS\d8.bat" --lib "$PLATFORM" --output "$BUILD_DIR\dex" $classFiles

Write-Host "5. Adding classes.dex to APK..."
& "$JDK\bin\jar.exe" -uf "$BUILD_DIR\unaligned.apk" -C "$BUILD_DIR\dex" classes.dex

Write-Host "6. Running zipalign..."
& "$BUILD_TOOLS\zipalign.exe" -f 4 "$BUILD_DIR\unaligned.apk" "$BUILD_DIR\aligned.apk"

$KEYSTORE = "$ANDROID_DIR\debug.keystore"
if (-not (Test-Path $KEYSTORE)) {
    Write-Host "Generating debug keystore..."
    & "$JDK\bin\keytool.exe" -genkeypair -v `
        -keystore "$KEYSTORE" `
        -storepass android `
        -alias androiddebugkey `
        -keypass android `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=PL"
}

Write-Host "7. Signing APK with apksigner..."
$FINAL_APK = "$ROOT\odu-manager.apk"
& "$BUILD_TOOLS\apksigner.bat" sign `
    --ks "$KEYSTORE" `
    --ks-pass pass:android `
    --key-pass pass:android `
    --out "$FINAL_APK" `
    "$BUILD_DIR\aligned.apk"

Write-Host "APK built successfully: $FINAL_APK"
Get-Item $FINAL_APK | Select-Object FullName, Length
