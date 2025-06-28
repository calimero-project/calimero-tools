import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
	`java-library`
	application
	`maven-publish`
	signing
	id("com.github.ben-manes.versions") version "0.52.0"
}

repositories {
	mavenLocal()
	mavenCentral()
	maven("https://oss.sonatype.org/content/repositories/snapshots")
	maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
}

group = "io.calimero"
version = "3.0-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
	options.encoding = "UTF-8"
}

application {
	mainModule.set("io.calimero.tools")
	mainClass.set(System.getProperty("mainClass") ?: "io.calimero.tools.Main")
}

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}

sourceSets {
	main {
		java.srcDirs("src")
		resources.srcDir("resources")
	}
	test {
		java.srcDirs("test")
	}

	create("serial")
	create("usb")
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
	withSourcesJar()
	withJavadocJar()

	registerFeature("serial") {
		usingSourceSet(sourceSets["serial"])
	}
	registerFeature("usb") {
		usingSourceSet(sourceSets["usb"])
	}
}

tasks.withType<Jar>().configureEach {
	from("$projectDir") {
		include("LICENSE.txt")
		into("META-INF")
	}
	if (name == "sourcesJar") {
		from("$projectDir") {
			include("README.md")
		}
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.compilerArgs.addAll(listOf(
		"-Xlint:all",
		"-Xlint:-try",
		"-Xlint:-options",
		"--add-reads", "io.calimero.tools=ALL-UNNAMED"
	))
}

tasks.named<JavaCompile>("compileJava") {
	options.javaModuleVersion = project.version.toString()
}

tasks.withType<Javadoc>().configureEach {
    (options as CoreJavadocOptions).addStringOption("Xdoclint:-missing", "-quiet")
    (options as CoreJavadocOptions).addStringOption("-add-reads", "io.calimero.tools=ALL-UNNAMED") // zip4j
}

val addReads = listOf(
	"--add-reads", "io.calimero.tools=ALL-UNNAMED", // zip4j
	"--add-reads", "io.calimero.core=io.calimero.tools", // @LinkEvent
	"--add-reads", "io.calimero.serial.provider.rxtx=ALL-UNNAMED",
	"--add-reads", "io.calimero.usb.provider.javax=ALL-UNNAMED"
)

// avoid jvm warning about native access
val enableNativeAccess = if (JavaLanguageVersion.current() >= JavaLanguageVersion.of(23))
		listOf("--enable-native-access", "serial.ffm") else listOf()

tasks.withType<JavaExec>().configureEach {
	jvmArgs(addReads)
	jvmArgs(enableNativeAccess)
	// add as root module because it is required by non-modularized usb4java
	jvmArgs("--add-modules", "org.apache.commons.lang3")
}

tasks.startScripts {
	defaultJvmOpts = addReads + enableNativeAccess

	val rtClasspath = configurations.runtimeClasspath.get().files
	val unixScriptFile = unixScript
	val winScriptFile = windowsScript
	doLast {
		fun File.replace(replace: String, with: String) = writeText(readText().replace(replace, with))

		// put commons-lang jar also on the classpath, used by libusb4java (which is in UNNAMED module)
		val commonsLang3 = rtClasspath.filter { it.name.startsWith("commons-lang3") }
		val libName = commonsLang3.first().name
		unixScriptFile.replace("\n\nMODULE_PATH=", ":${'$'}APP_HOME/lib/$libName\n\nMODULE_PATH=")
		winScriptFile.replace("\r\nset MODULE_PATH=", ";%APP_HOME%\\lib\\$libName\r\nset MODULE_PATH=")
	}
}

dependencies {
	api("io.calimero:calimero-core:$version")
	implementation("net.lingala.zip4j:zip4j:2.11.5")

	add("serialRuntimeOnly", "io.calimero:calimero-rxtx:$version")
	add("serialRuntimeOnly", "io.calimero:serial-native:$version")
	if (JavaLanguageVersion.current() >= JavaLanguageVersion.of(23))
		add("serialRuntimeOnly", "io.calimero:calimero-serial-ffm:$version")
	add("usbRuntimeOnly", "io.calimero:calimero-usb:$version")
	runtimeOnly(sourceSets["serial"].runtimeClasspath)
	runtimeOnly(sourceSets["usb"].runtimeClasspath)

	runtimeOnly("org.slf4j:slf4j-jdk-platform-logging:2.0.17")
	runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// we don't need the serial/usb feature jars when publishing
tasks.named("serialJar") { enabled = false }
tasks.named("usbJar") { enabled = false }

tasks.named<Jar>("jar") {
	manifest {
		val gitHash = providers.exec {
			commandLine("git", "-C", "$projectDir", "rev-parse", "--verify", "--short", "HEAD")
		}.standardOutput.asText.map { it.trim() }

		val buildDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
			.withZone(ZoneId.of("UTC"))
			.format(Instant.now())

		attributes(
			"Main-Class" to application.mainClass.get(),
			"Implementation-Version" to project.version,
			"Revision" to gitHash.get(),
			"Build-Date" to buildDate,
			"Class-Path" to configurations.runtimeClasspath.get().files.joinToString(" ") { it.name }
		)
	}
}

tasks.distTar {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.distZip {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = rootProject.name
			from(components["java"])
			pom {
				name.set("Calimero Tools")
				description.set("A collection of tools for KNX network communication")
				url.set("https://github.com/calimero-project/calimero-tools")
				inceptionYear.set("2006")
				licenses {
					license {
						name.set("GNU General Public License, version 2, with the Classpath Exception")
						url.set("LICENSE.txt")
					}
				}
				developers {
					developer {
						name.set("Boris Malinowsky")
						email.set("b.malinowsky@gmail.com")
					}
				}
				scm {
					connection.set("scm:git:git://github.com/calimero-project/calimero-tools.git")
					url.set("https://github.com/calimero-project/calimero-tools.git")
				}
			}
		}
	}
	repositories {
		maven {
			name = "maven"
			val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
			val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
			url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
			credentials(PasswordCredentials::class)
		}
	}
}

signing {
	if (project.hasProperty("signing.keyId")) {
		sign(publishing.publications["mavenJava"])
	}
}
