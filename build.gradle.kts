import org.gradle.jvm.tasks.Jar

allprojects {
	group = if (path == ":") "com.refinvest" else "com.refinvest${path.replace(':', '.')}"
	version = "0.0.1-SNAPSHOT"

	tasks.withType<Jar>().configureEach {
		archiveBaseName.set(project.path.removePrefix(":").replace(':', '-'))
	}
}
