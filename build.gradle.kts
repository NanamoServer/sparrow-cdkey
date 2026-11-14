import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    java
    id("de.eldoria.plugin-yml.paper") version "0.9.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "top.catnies.sparrow.cdkey"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-20251209.165129-1")
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0") { isTransitive = false }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release = 21
}

paper {
    name = "SparrowCdkey"
    main = "top.catnies.sparrow.cdkey.SparrowCdkeyPlugin"
    apiVersion = "1.21.11"
    description = "Local CDKEY management and redemption for Paper."
    permissions {
        register("sparrow.command.cdkey.view") {
            description = "View CDKEY definitions and redemption history."
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("sparrow.command.cdkey.manage") {
            description = "Create and edit definitions and generate batches."
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("sparrow.command.cdkey.copy") {
            description = "View and copy unused generated codes."
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("sparrow.command.cdkey.delete") {
            description = "Preview and delete definitions and their records."
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("sparrow.command.redeem") {
            description = "Open the redemption dialog and submit a code."
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("sparrow.command.redeem.other") {
            description = "Open the redemption dialog for an online player."
            default = BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.jar {
    from("LICENSE")
}

tasks.runServer {
    minecraftVersion("1.21.11")
    runDirectory.set(layout.projectDirectory.dir("test/runServer"))
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    })
}
