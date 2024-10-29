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
	id("com.github.ben-manes.versions") version "0.51.0"
}

repositories {
	mavenLocal()
	mavenCentral()
	maven("https://oss.sonatype.org/content/repositories/snapshots")
	maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
}

group = "com.github.calimero"
version = "2.6-rc1"

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
	options.encoding = "UTF-8"
}

application {
	mainClass.set(System.getProperty("mainClass") ?: "tuwien.auto.calimero.tools.Main")
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
		"-Xlint:-options"
	))
}

tasks.named<JavaCompile>("compileTestJava") {
	options.compilerArgs.addAll(listOf(
		"-Xlint:all",
		"-Xlint:-try"
	))
}

tasks.withType<Javadoc>().configureEach {
    (options as CoreJavadocOptions).addStringOption("Xdoclint:-missing", "-quiet")
}

dependencies {
	api("com.github.calimero:calimero-core:$version")
	implementation("net.lingala.zip4j:zip4j:2.11.5")
	implementation("org.slf4j:slf4j-api:2.0.12")

	add("serialRuntimeOnly", "com.github.calimero:calimero-rxtx:$version")
	add("serialRuntimeOnly", "io.calimero:serial-native:$version")
	add("usbRuntimeOnly", "io.calimero:calimero-usb:$version")
	runtimeOnly(sourceSets["serial"].runtimeClasspath)
	runtimeOnly(sourceSets["usb"].runtimeClasspath)

	runtimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

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
			val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
			val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
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
