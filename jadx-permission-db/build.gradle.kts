plugins {
	id("jadx-java")
	id("application")
}

description = "Generator for the Android API to permission database used by jadx-gui"

dependencies {
	implementation(libs.gson)
	implementation(libs.sqlite.jdbc)

	runtimeOnly(libs.logback.classic)
}

application {
	mainClass.set("jadx.permdb.PermissionDbGenerator")
}

tasks.named<JavaExec>("run") {
	// relative paths in arguments are resolved from the repository root
	workingDir = rootDir
}
