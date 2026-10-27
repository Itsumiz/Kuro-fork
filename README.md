# Kuro

Fabric utility mod for Minecraft 1.21.11.

## Building

### Prerequisites
- JDK 21 or later

### Build with script

Linux / macOS:
```bash
./build.sh
```

Windows:
```cmd
build.bat
```

The build scripts will check for cached Loom assets from local client setups (CLIclient, Open-respect, Respect-Bpp) to build offline without re-downloading Minecraft and Loom mapping files. If no local cache is found, it will run standard Gradle build online.

### Build with Gradle

```bash
./gradlew assemble
```

### Outputs

Build artifacts are located in `build/libs/`:
- `kuro-<version>.jar` (mod jar)
- `kuro-<version>-sources.jar` (sources jar)
- `kuro-<version>-src.zip` (source zip archive)
