# Installation
FarAway is an experimental client mod for Minecraft Java Edition 26.3.

## Requirements
- JDK 25
- Gradle 9.6.0
- Fabric Loader 0.20.1 or newer 
- Fabric API for Minecraft 26.3
- Sodium 0.9.2 for Minecraft 26.3

## Build and run
Set `JAVA_HOME` to JDK 25, make Gradle available on your `PATH`, and run from the project root:
```sh
gradle build
gradle -PfarawayWorldGrid runClient
```

Build output is in `build/libs/`. To install the JAR, use a Minecraft 26.3 Fabric profile and place the JAR compatible Fabric API, and Sodium 0.9.2 in its `mods` folder. The normal launcher profile has not yet been verified.

On Windows, `start-client.cmd` also works if JDK 25 is installed under `.tools/java/` and Gradle 9.6.0 under `.tools/gradle-9.6.0/`. These local tools are not included in the repository.

Open the mod settings in-game with `/faraway settings`. See `LICENSE` for usage terms.
